package mojoz.metadata.in

import java.io.File
import scala.Array.canBuildFrom
import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.JavaConverters.enumerationAsScalaIteratorConverter
import scala.io.Source
import scala.xml.PrettyPrinter
import mojoz.metadata._
import mojoz.metadata.io._
import mojoz.metadata.TableDef._
import org.yaml.snakeyaml.Yaml

private[in] case class YamlTableDef(
  table: String,
  comments: String,
  columns: Seq[YamlFieldDef])

private[in] case class YamlFieldDef(
  name: String,
  cardinality: String,
  maxOccurs: Option[Int],
  typeName: String,
  length: Option[Int],
  fraction: Option[Int],
  isExpression: Boolean,
  expression: String,
  enum: Seq[String],
  joinToParent: String,
  orderBy: String,
  comments: String)

class YamlTableDefLoader(yamlMd: Seq[YamlMd],
  conventions: MdConventions = new MdConventions) {
  val sources = yamlMd.filter(YamlMd.isTableDef)
  private def checkTableDefs(td: Seq[TableDef[_]]) = {
    val m: Map[String, _] = td.map(t => (t.name, t)).toMap
    if (m.size < td.size) sys.error("repeating definition of " +
      td.groupBy(_.name).filter(_._2.size > 1).map(_._1).mkString(", "))
    def checkName(t: TableDef[_]) =
      if (t.name == null || t.name.trim == "") sys.error("Table without name")
    def checkHasColumns(t: TableDef[_]) =
      if (t.cols == null || t.cols.size == 0) sys.error(
        "Table " + t.name + " has no columns")
    def checkRepeatingColumnNames(t: TableDef[_]) =
      if (t.cols.map(_.name).toSet.size < t.cols.size) sys.error(
        "Table " + t.name + " defines multiple columns named " + t.cols
          .groupBy(_.name).filter(_._2.size > 1).map(_._1).mkString(", "))
    td foreach checkName
    td foreach checkHasColumns
    td foreach checkRepeatingColumnNames
  }
  val tableDefs = try {
    val rawTableDefs = sources map { md =>
      try yamlTypeDefToTableDef(loadYamlTableDef(md.body)) catch {
        case e: Exception => throw new RuntimeException(
          "Failed to load typedef from " + md.filename, e) // TODO line number
      }
    }
    checkTableDefs(rawTableDefs)

    def resolvedName(n: String) = n.replace('.', '_')
    val nameToTableDef = rawTableDefs.map(t => (t.name, t)).toMap
    def refToCol(ref: String) =
      (ref.split("\\.", 2).toList match {
        case t :: c :: Nil => Some((t, c)) case x => None
      })
        .flatMap(tc => nameToTableDef.get(tc._1).map((_, tc._2)))
        .flatMap(tc => tc._1.cols.find(c => resolvedName(c.name) == tc._2)
          .map(c => (tc._1, c)))
        .getOrElse(sys.error("Bad ref: " + ref))
    def overwriteXsdType(base: Type, overwrite: Type) = Type(
      Option(base.name) getOrElse overwrite.name,
      overwrite.length orElse base.length,
      overwrite.totalDigits orElse base.totalDigits,
      overwrite.fractionDigits orElse base.fractionDigits,
      false)
    def baseRefChain(col: ColumnDef[Type], visited: List[String]): List[String] = {
      def chain(ref: String) =
        if (visited contains ref)
          sys.error("Cyclic column refs: " +
            (ref :: visited).reverse.mkString(" -> "))
        else baseRefChain(refToCol(ref)._2, ref :: visited)
      // TODO ??? if type explicitly defined, why ref? throw?
      if (Option(col.type_.name).map(_ contains ".") getOrElse false)
        chain(col.type_.name)
      else if (col.name contains ".") chain(col.name)
      else visited
    }
    val tableDefs = rawTableDefs map { r =>
      val resolvedColsAndRefs: Seq[(ColumnDef[Type], Seq[Ref])] = r.cols.map { c =>
        val refChain = baseRefChain(c, Nil)
        if (refChain.size == 0) (c, Nil)
        else {
          // TODO how about multi-col refs, how to define/use these?
          // TODO PKs, FKs and their names? By conventions!
          val defaultRefTableAlias =
            if (c.type_.name == null || c.name.indexOf('.') < 0) null
            else c.name.substring(0, c.name.indexOf('.'))
          val colName = c.name.replace('.', '_')
          val (refTable, refCol) =
            refToCol(Option(c.type_.name) getOrElse c.name)
          val xsdType = overwriteXsdType(
            refChain.foldLeft(new Type(null))((t, ref) =>
              overwriteXsdType(t, refToCol(ref)._2.type_)),
            c.type_)
          val ref = Ref(null, List(colName), refTable.name, List(refCol.name),
            null, defaultRefTableAlias, null, null)
          (c.copy(name = colName, type_ = xsdType), List(ref))
        }
      }
      r.copy(
        cols = resolvedColsAndRefs.map(_._1),
        refs = resolvedColsAndRefs.flatMap(_._2))
    }
    tableDefs
  }
  private def loadYamlTableDef(typeDef: String) = {
    val tdMap = mapAsScalaMap(
      (new Yaml).load(typeDef).asInstanceOf[java.util.Map[String, _]]).toMap
    val table = tdMap.get("table").map(_.toString)
      .getOrElse(sys.error("Missing table name"))
    val comment = tdMap.get("comment").map(_.toString) getOrElse null
    val colSrc = tdMap.get("columns")
      .filter(_ != null)
      .map(m => m.asInstanceOf[java.util.ArrayList[_]].toList)
      .getOrElse(Nil)
    val colDefs = colSrc map YamlMdLoader.loadYamlFieldDef
    YamlTableDef(table, comment, colDefs)
  }
  private def yamlTypeDefToTableDef(y: YamlTableDef) = {
    val name = y.table
    val comment = y.comments
    val cols = y.columns.map(yamlFieldDefToExFieldDef)
    val pk = None // TODO primary key
    val uk = Nil // TODO unique indexes
    val idx = Nil // TODO indexes
    val refs = Nil // TODO refs?
    val exTypeDef = TableDef(name, comment, cols, pk, uk, idx, refs)
    conventions.fromExternal(exTypeDef)
  }
  private def yamlFieldDefToExFieldDef(yfd: YamlFieldDef) = {
    val name = yfd.name
    if (yfd.maxOccurs.isDefined)
      sys.error("maxOccurs not supported for table columns")
    val dbDefault = yfd.expression
    val nullable = yfd.cardinality match {
      case null => None
      case "?" => Some(true)
      case "!" => Some(false)
      case x =>
        sys.error("Unexpected cardinality for table column: " + x)
    }
    val enum = yfd.enum
    if (yfd.joinToParent != null)
      sys.error("joinToParent not supported for table columns")
    if (yfd.orderBy != null)
      sys.error("orderBy not supported for table columns")
    val comment = yfd.comments
    val rawXsdType = Option(YamlMdLoader.xsdType(yfd, conventions))
    ColumnDef(name, IoColumnType(nullable, rawXsdType),
      nullable getOrElse true, dbDefault, enum, comment)
  }
}

private[in] object YamlMdLoader {
  val FieldDef = {
    val ident = "[_a-zA-z][_a-zA-Z0-9]*"
    val qualifiedIdent = <a>{ ident }(\.{ ident })?</a>.text
    val int = "[0-9]+"
    val s = "\\s*"

    val name = qualifiedIdent
    val quant = "([\\?\\!]|([\\*\\+](\\.\\.(\\d*[1-9]\\d*))?))"
    val join = "\\[.*?\\]"
    val order = "\\~?#(\\s*\\(.*?\\))?"
    val enum = "\\(.*?\\)"
    val typ = qualifiedIdent
    val len = int
    val frac = int
    val expr = ".*"
    val pattern =
      <a>
        ({name})({s}{quant})?({s}{join})?({s}{typ})?({s}{len})?({s}{frac})?({s}{order})?({s}{enum})?({s}=({expr})?)?
      </a>.text.trim

    ("^" + pattern + "$").r
  }

  def loadYamlFieldDef(src: Any) = {
    val ThisFail = "Failed to load column definition"
    def colDef(nameEtc: String, comment: String) = nameEtc match {
      case FieldDef(name, _, quant, _, _, _, maxOcc, joinToParent, typ, _,
        len, frac, order, _, enum, isExpr, expr) =>
        def t(s: String) = Option(s).map(_.trim).filter(_ != "").orNull
        def i(s: String) = Option(s).map(_.trim.toInt)
        def e(enum: String) = Option(enum)
          .map(_ split "[\\(\\)\\s,]+")
          .map(_.toList.filter(_ != ""))
          .filter(_.size > 0).orNull
        def cardinality = Option(t(quant)).map(_.take(1)).orNull
        YamlFieldDef(name, cardinality, i(maxOcc), t(typ), i(len), i(frac),
          isExpr != null, t(expr), e(enum), t(joinToParent), t(order), comment)
      case _ => throw new RuntimeException(ThisFail +
        " - unexpected format: " + nameEtc.trim)
    }
    src match {
      case nameEtc: java.lang.String =>
        colDef(nameEtc.toString, null)
      case x: java.util.Map[_, _] =>
        val m = x.asInstanceOf[java.util.Map[_, _]]
        if (m.size == 1) {
          val entry = m.entrySet.toList(0)
          val nameEtc = entry.getKey
          val comment = entry.getValue
          colDef(nameEtc.toString, comment.toString)
        } else throw new RuntimeException(ThisFail +
          " - more than one entry for column: " + m.toMap.toString())
      case x => throw new RuntimeException(ThisFail +
        " - unexpected field definition class: " + x.getClass
        + "\nentry: " + x.toString)
    }
  }

  def xsdType(f: YamlFieldDef,
    conventions: MdConventions) = (f.typeName, f.length, f.fraction) match {
    // FIXME do properly (check unsupported patterns, ...)
    // FIXME TODO complex types
    case (null, None, None) => null
    case (null, Some(len), None) => new Type(null, len)
    case (null, Some(len), Some(frac)) => new Type("decimal", len, frac)
    case ("anySimpleType", _, _) => new Type("anySimpleType")
    case ("date", _, _) => new Type("date")
    case ("dateTime", _, _) => new Type("dateTime")
    case ("string", None, _) => new Type("string")
    case ("string", Some(len), _) => new Type("string", len)
    case ("boolean", _, _) => new Type("boolean")
    case ("int", None, _) => new Type("int")
    case ("int", Some(len), _) => Type("int", None, Some(len), None, false)
    case ("long", None, _) => new Type("long")
    case ("long", Some(len), _) => Type("long", None, Some(len), None, false)
    case ("decimal", None, None) => new Type("decimal")
    case ("decimal", Some(len), None) => new Type("decimal", len, 0)
    case ("decimal", Some(len), Some(frac)) => new Type("decimal", len, frac)
    case ("base64Binary", None, _) => new Type("base64Binary")
    case ("base64Binary", Some(len), _) => new Type("base64Binary", len)
    case ("anyType", _, _) => new Type("anyType")
    case (x, len, frac) if conventions.isRefName(x) =>
      Type(x, len, None, frac, false) // FIXME len <> totalDigits, resolve!
    // if no known xsd type name found - let it be complex type!
    case (x, _, _) => new Type(x, true)
  }
}
