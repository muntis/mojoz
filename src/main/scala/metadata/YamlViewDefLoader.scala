package metadata

import java.io.File
import java.util.ArrayList

import scala.Array.canBuildFrom
import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.JavaConverters.enumerationAsScalaIteratorConverter
import scala.collection.mutable.Queue
import scala.io.Source
import scala.reflect.BeanProperty

import org.yaml.snakeyaml.Yaml

import metadata.DbConventions.{ xsdNameToDbName => dbName }
import org.tresql.{ Env, QueryParser }

case class XsdTypeDef(
  name: String,
  table: String,
  tableAlias: String,
  joins: String, // tresql from clause
  filter: String,
  xtnds: String,
  draftOf: String,
  detailsOf: String,
  comment: String,
  fields: Seq[XsdFieldDef])

case class XsdFieldDef(
  table: String,
  tableAlias: String,
  name: String,
  alias: String,
  isCollection: Boolean,
  maxOccurs: String,
  isExpression: Boolean,
  isFilterable: Boolean,
  expression: String,
  nullable: Boolean,
  isForcedCardinality: Boolean,
  xsdType: XsdType,
  enum: Seq[String],
  joinToParent: String,
  orderBy: String,
  isI18n: Boolean,
  comment: String) {
  val isSimpleType = xsdType == null || !xsdType.isComplexType
  val isComplexType = xsdType != null && xsdType.isComplexType
}

trait ViewDefSource {
  val typedefs: List[XsdTypeDef]
  // typedef name to typedef
  val nameToViewDef: Map[String, XsdTypeDef]
  // typedef name to typedef with extended field list
  val nameToExtendedViewDef: Map[String, XsdTypeDef]
}

trait YamlViewDefLoader extends ViewDefSource {
  this: RawViewDefSource with Metadata with I18nRules =>

  val typedefStrings = getRawViewDefs
  private val rawTypeDefs = typedefStrings map { md =>
    try loadRawTypeDef(md.body) catch {
      case e: Exception => throw new RuntimeException(
        "Failed to load typedef from " + md.filename, e) // TODO line number
    }
  }
  private val nameToRawTypeDef = rawTypeDefs.map(t => (t.name, t)).toMap
  @tailrec
  private def baseTable(t: XsdTypeDef,
    nameToTypeDef: collection.Map[String, XsdTypeDef],
    visited: List[String]): String =
    if (visited contains t.name)
      sys.error("Cyclic extends: " +
        (t.name :: visited).reverse.mkString(" -> "))
    else if (t.table != null) t.table
    else baseTable(nameToTypeDef.get(t.xtnds)
      .getOrElse(sys.error("base table not found, type: " + t.name)),
      nameToTypeDef, t.name :: visited)
  val typedefs = buildTypeDefs(rawTypeDefs).sortBy(_.name)
  def loadRawTypeDef(typeDef: String) = {
    val tdMap = mapAsScalaMap(
      (new Yaml).load(typeDef).asInstanceOf[java.util.Map[String, _]]).toMap
    def get(name: String) = tdMap.get(name).map(_.toString) getOrElse null
    val rawName = get("name")
    val rawTable = get("table")
    val joins = get("joins")
    val filter = get("filter")
    val xtnds = get("extends")
    val draftOf = get("draft-of")
    val detailsOf = get("details-of")
    val comment = get("comment")
    val fieldsSrc = tdMap.get("fields")
      .map(m => m.asInstanceOf[java.util.ArrayList[_]].toList)
      .getOrElse(Nil)
    val extendsOrModifies =
      Option(xtnds).orElse(Option(detailsOf)).getOrElse(draftOf)
    val (name, table) = (rawName, rawTable, extendsOrModifies) match {
      case (name, null, null) => (name, dbName(name))
      case (name, null, _) => (name, null)
      case (null, table, null) => (table, dbName(table))
      case (name, table, _) => (name, dbName(table))
    }
    if (List(xtnds, draftOf, detailsOf).filter(_ != null).size > 1) sys.error(
      "extends, draft-of, details-of are not supported simultaneously, type: " + name)
    val yamlFieldDefs = fieldsSrc map YamlMdLoader.loadYamlFieldDef
    def toXsdFieldDef(yfd: YamlFieldDef) = {
      val table = null
      val tableAlias = null
      val name = yfd.name
      val alias = null
      val isCollection = Set("*", "+").contains(yfd.cardinality)
      val maxOccurs = yfd.maxOccurs.map(_.toString).orNull
      val isExpression = yfd.isExpression
      val expression = yfd.expression
      // if expression consists of a call to function attached to Env, 
      // then we consider is not filterable, otherwise consider it db function and filterable
      val isFilterable = if (!isExpression) true
      else if (expression == null) false
      else QueryParser.parseExp(expression) match {
	    case QueryParser.Fun(f, p, _) 
	    if Env.isDefined(f) && Env.functions.flatMap(_.getClass.getMethods.filter(
	      m => m.getName == f && m.getParameterTypes.length == p.size
	    ).headOption) != None 
	      => false
	    case _ => true
	  }
      val nullable = Option(yfd.cardinality)
        .map(c => Set("?", "*").contains(c)) getOrElse true
      val isForcedCardinality = yfd.cardinality != null
      val joinToParent = yfd.joinToParent
      val enum = yfd.enum
      val orderBy = yfd.orderBy
      val comment = yfd.comment
      val rawXsdType = Option(YamlMdLoader.xsdType(yfd))
      val xsdTypeFe =
        if (isExpression)
          MdConventions.fromExternal(
            // XXX unnecessary complex structure used
            ExFieldDef(name, rawXsdType, None, null, null, comment)).xsdType
        else null
      val xsdType =
        if (xsdTypeFe != null) xsdTypeFe else rawXsdType getOrElse null

      XsdFieldDef(table, tableAlias, name, alias, isCollection, maxOccurs,
        isExpression, isFilterable, expression, nullable, isForcedCardinality,
        xsdType, enum, joinToParent, orderBy, false, comment)
    }
    XsdTypeDef(name, table, null, joins, filter, xtnds, draftOf, detailsOf, comment,
      yamlFieldDefs map toXsdFieldDef)
  }
  private def checkTypedefs(td: Seq[XsdTypeDef]) = {
    val m = td.map(t => (t.name, t)).toMap
    if (m.size < td.size) sys.error("repeating definition of " +
      td.groupBy(_.name).filter(_._2.size > 1).map(_._1).mkString(", "))
    @tailrec
    def checkExtends(t: XsdTypeDef, nameToTypeDef: Map[String, XsdTypeDef],
      visited: List[String]): Boolean = {
      val extendsOrModifies =
        Option(t.xtnds).orElse(Option(t.detailsOf)).getOrElse(t.draftOf)
      if (visited contains t.name) sys.error("Cyclic extends: " +
        (t.name :: visited).reverse.mkString(" -> "))
      else if (extendsOrModifies == null) true
      else checkExtends(nameToTypeDef.get(extendsOrModifies)
        .getOrElse(sys.error("Type " + t.name +
          " extends or modifies non-existing type " + extendsOrModifies)),
        nameToTypeDef, t.name :: visited)
    }
    td.foreach(t => checkExtends(t, m, Nil))
    def propName(f: XsdFieldDef) = Option(f.alias) getOrElse f.name
    def checkRepeatingFieldNames(t: XsdTypeDef) =
      if (t.fields.map(propName).toSet.size < t.fields.size) sys.error(
        "Type " + t.name + " defines multiple fields named " + t.fields
          .groupBy(propName).filter(_._2.size > 1).map(_._1).mkString(", "))
    td foreach checkRepeatingFieldNames
    // check field names not repeating on extends? or overwrite instead?
  }
  private def checkTypedefMapping(td: Seq[XsdTypeDef]) = {
    val m = td.map(t => (t.name, t)).toMap
    td foreach { t =>
      t.fields.foreach { f =>
        if (f.xsdType.isComplexType)
          m.get(f.xsdType.name) getOrElse sys.error("Type " + f.xsdType.name +
            " referenced from " + t.name + " is not found")
        else if (!f.isExpression) getCol(t, f)
      }
    }
  }
  def draftName(n: String) = // XXX
    if (n endsWith "_details") n.replace("_details", "_draft_details")
    else n + "_draft"
  def detailsName(n: String) = n + "_details"
  private def buildTypeDefs(rawTypeDefs: Seq[XsdTypeDef]) = {
    //checkTypedefs(rawTypeDefs) FIXME does not allow draft names in type hierarchy
    val rawTypesMap = rawTypeDefs.map(t => (t.name, t)).toMap
    val resolvedTypes = new collection.mutable.ArrayBuffer[XsdTypeDef]()
    val resolvedTypesMap = collection.mutable.Map[String, XsdTypeDef]()

    def inheritTable(t: XsdTypeDef) =
      if (t.table != null) t
      else t.copy(table = baseTable(t, resolvedTypesMap, Nil))

    def inheritJoins(t: XsdTypeDef) = {
      @tailrec
      def inheritedJoins(t: XsdTypeDef): String =
        if (t.joins != null || t.xtnds == null) t.joins
        else inheritedJoins(m(t.xtnds))
      if (t.xtnds == null) t
      else t.copy(joins = inheritedJoins(t))
    }

    def inheritFilter(t: XsdTypeDef) = {
      @tailrec
      def inheritedFilter(t: XsdTypeDef): String =
        if (t.filter != null || t.xtnds == null) t.filter
        else inheritedFilter(m(t.xtnds))
      if (t.xtnds == null) t
      else t.copy(filter = inheritedFilter(t))
    }

    def resolveBaseTableAlias(t: XsdTypeDef) = t.copy(tableAlias =
      JoinsParser(t.table, t.joins).filter(_.table == t.table).toList match {
        case Join(a, _, _) :: Nil => // if only one base table encountered return alias
          Option(a) getOrElse t.table
        case _ => "b" // default base table alias 
      })

    def resolveFieldNamesAndTypes(t: XsdTypeDef) = {
      val joins = JoinsParser(t.table, t.joins)
      val aliasToTable =
        joins.filter(_.alias != null).map(j => j.alias -> j.table).toMap
      val tableOrAliasToJoin =
        joins.map(j => Option(j.alias).getOrElse(j.table) -> j).toMap
      def resolveNameAndTable(f: XsdFieldDef) =
        if (f.name.indexOf(".") < 0)
          f.copy(table = dbName(t.table), name = dbName(f.name))
        else {
          val parts = f.name.split("\\.")
          val tableOrAlias = dbName(parts(0))
          val table = dbName(aliasToTable.getOrElse(tableOrAlias, tableOrAlias))
          val tableAlias = if (table == tableOrAlias) null else tableOrAlias
          val name = dbName(parts(1))
          def maybeNoPrefix(fName: String) = fName.indexOf("_.") match {
            case -1 => fName
            case rmIdx => fName.substring(rmIdx + 2)
          }
          val alias = dbName(maybeNoPrefix(f.name).replace(".", "_"))
          f.copy(table = table, tableAlias = tableAlias,
            name = name, alias = alias)
        }
      def resolveTypeFromDbMetadata(f: XsdFieldDef) = {
        if (f.isExpression || f.isCollection) f
        else {
          val col = getCol(t, f)
          val tableOrAlias = Option(f.tableAlias) getOrElse f.table
          // FIXME autojoins nullable?
          val nullable =
            if (f.isForcedCardinality) f.nullable
            else tableOrAliasToJoin.get(tableOrAlias).map(_.nullable)
              .getOrElse(Right(col.nullable)) match {
                case Right(b) => b || col.nullable
                case Left(s) => true // FIXME Left(nullableTableDependency)!
              }
          f.copy(nullable = nullable, xsdType = col.xsdType,
            enum = Option(f.enum) getOrElse col.enum,
            comment = Option(f.comment) getOrElse col.comment)
        }
      }
      t.copy(fields = t.fields
        .map(resolveNameAndTable)
        .map(resolveTypeFromDbMetadata))
    }

    // drafts logic
    def resolveDraft(draft: XsdTypeDef, addMissing: (XsdTypeDef) => Any) = {
      def canReuseSimpleFieldsForDraft(t: XsdTypeDef) = {
        // FIXME this is not exactly correct, or should be pluggable
        !t.fields
          .filter(_.isSimpleType)
          .filter(!_.isCollection)
          .filter(!_.nullable)
          .exists(_.isForcedCardinality)
      }
      def canReuseComplexFieldsForDraft(t: XsdTypeDef) = {
        !t.fields
          .filter(_.isComplexType)
          .exists(f => !canReuseAsDraft(m(f.xsdType.name)))
      }
      def canReuseFieldsForDraft(t: XsdTypeDef) =
        canReuseSimpleFieldsForDraft(t) && canReuseComplexFieldsForDraft(t)
      def canReuseAsDraft(t: XsdTypeDef): Boolean =
        canReuseFieldsForDraft(t) &&
          (t.xtnds == null || canReuseAsDraft(m(t.xtnds)))
      def addMissingDraftOf(draftOf: XsdTypeDef) = addMissing(
        draftOf.copy(name = draftName(draftOf.name), table = null, joins = null,
          xtnds = null, draftOf = draftOf.name, fields = Nil))
      def draftField(f: XsdFieldDef) =
        if (f.isComplexType) {
          val t = m(f.xsdType.name)
          def fCopy = f.copy(xsdType = f.xsdType.copy(name = draftName(t.name)))
          if (isDefined(draftName(t.name))) fCopy
          else if (canReuseAsDraft(t)) f
          else { addMissingDraftOf(t); fCopy }
        } else if (f.isForcedCardinality && !f.nullable && !f.isCollection)
          f.copy(nullable = true)
        else f
      val draftOf = m(draft.draftOf)
      if (canReuseAsDraft(draftOf))
        draft.copy(xtnds = draftOf.name, draftOf = null)
      else {
        val xDraftName = Option(draftOf.xtnds).map(draftName).orNull
        val xtnds =
          if (draftOf.xtnds == null) null
          else if (isDefined(xDraftName)) xDraftName
          else if (canReuseAsDraft(m(draftOf.xtnds))) draftOf.xtnds
          else { addMissingDraftOf(m(draftOf.xtnds)); xDraftName }
        val table = Option(draft.table) getOrElse draftOf.table
        val joins = Option(draft.joins) getOrElse draftOf.joins
        draft.copy(
          table = table, joins = joins, xtnds = xtnds, draftOf = null,
          fields = draftOf.fields.map(draftField) ++ draft.fields)
      }
    }

    // details logic
    def resolveDetails(details: XsdTypeDef, addMissing: (XsdTypeDef) => Any) = {
      def hasChildrenWithDetails(t: XsdTypeDef): Boolean =
        t.fields
          .filter(_.isComplexType)
          .map(_.xsdType)
          .filter(t =>
            isDefined(detailsName(t.name)) || hasChildrenWithDetails(m(t.name)))
          .size > 0
      def canReuseFieldsForDetails(t: XsdTypeDef) = !hasChildrenWithDetails(t)
      def canReuseAsDetailsSuper(t: XsdTypeDef): Boolean =
        canReuseFieldsForDetails(t) &&
          (t.xtnds == null || canReuseAsDetails(m(t.xtnds)))
      def canReuseAsDetails(t: XsdTypeDef): Boolean =
        !isDefined(detailsName(t.name)) && canReuseAsDetailsSuper(t)
      def addMissingDetailsOf(dtOf: XsdTypeDef) = addMissing(
        dtOf.copy(name = detailsName(dtOf.name), table = null, joins = null,
          xtnds = null, detailsOf = dtOf.name, fields = Nil))
      def detailsField(f: XsdFieldDef) = if (f.isSimpleType) f else {
        val t = m(f.xsdType.name)
        def fCopy = f.copy(xsdType = f.xsdType.copy(name = detailsName(t.name)))
        if (isDefined(detailsName(t.name))) fCopy
        else if (canReuseAsDetails(t)) f
        else { addMissingDetailsOf(t); fCopy }
      }
      val detailsOf = m(details.detailsOf)
      if (canReuseAsDetailsSuper(detailsOf))
        details.copy(xtnds = detailsOf.name, detailsOf = null)
      else {
        val xDetailsName = Option(detailsOf.xtnds).map(detailsName).orNull
        val xtnds =
          if (detailsOf.xtnds == null) null
          else if (isDefined(xDetailsName)) xDetailsName
          else if (canReuseAsDetails(m(detailsOf.xtnds))) detailsOf.xtnds
          else { addMissingDetailsOf(m(detailsOf.xtnds)); xDetailsName }
        val table = Option(details.table) getOrElse (
          if (xtnds == null) detailsOf.table else null)
        val joins = Option(details.joins) getOrElse (
          if (xtnds == null) detailsOf.joins else null)
        details.copy(
          table = table, joins = joins, xtnds = xtnds, detailsOf = null,
          fields = detailsOf.fields.map(detailsField) ++ details.fields)
      }
    }

    def isDefined(tName: String) = rawTypesMap.contains(tName)
    def typeResolved(t: XsdTypeDef) = {
      resolvedTypes += t
      resolvedTypesMap(t.name) = t
      t
    }
    def addMissing(t: XsdTypeDef) = {
      // println("Adding missing type: " + t.name) // TODO not distinct
      resolveType(t)
    }
    // TODO add stack overflow protection
    def m(tName: String) = resolveTypeByName(tName)
    def resolveTypeByName(tName: String): XsdTypeDef =
      resolvedTypesMap.getOrElse(tName,
        resolveUnresolvedType(rawTypesMap(tName)))
    def resolveType(t: XsdTypeDef): XsdTypeDef =
      resolvedTypesMap.getOrElse(t.name, resolveUnresolvedType(t))
    def resolveUnresolvedType(t: XsdTypeDef): XsdTypeDef = typeResolved {
      (t.draftOf, t.detailsOf) match {
        case (null, null) => t
        case (draftOf, null) => resolveDraft(t, addMissing)
        case (null, detailsOf) => resolveDetails(t, addMissing)
      }
    }

    rawTypeDefs foreach resolveType
    val result = resolvedTypes.toList
      .map(inheritTable)
      .map(inheritJoins)
      .map(inheritFilter)
      .map(resolveBaseTableAlias)
      .map(resolveFieldNamesAndTypes)
    checkTypedefs(result)
    checkTypedefMapping(result)
    result
  }

  // typedef name to typedef
  val nameToViewDef = typedefs.map(t => (t.name, t)).toMap
  // typedef name to typedef with extended field list
  val nameToExtendedViewDef = typedefs.map(t =>
    if (t.xtnds == null) t else {
      @tailrec
      def baseFields(t: XsdTypeDef, fields: Seq[XsdFieldDef]): Seq[XsdFieldDef] =
        if (t.xtnds == null) t.fields ++ fields
        else baseFields(nameToViewDef(t.xtnds), t.fields ++ fields)
      t.copy(fields = baseFields(t, Nil))
    })
    .map(setI18n)
    .map(t => (t.name, t)).toMap
}
