package mojoz.metadata.out

import mojoz.metadata.DbConventions.{ dbNameToXsdName => xsdName }
import mojoz.metadata.FieldDef.{ FieldDefBase => FieldDef }
import mojoz.metadata.Type
import mojoz.metadata.ViewDef.{ ViewDefBase => ViewDef }

trait ScalaClassWriter {
  def nl = System.getProperty("line.separator")
  def scalaClassName(name: String) = xsdName(name)
  def scalaFieldName(name: String) = xsdName(name) match {
    case x if x.length == 1 || (x.length > 1 && (x(1).isLower || x(1).isDigit)) =>
      x(0).toLower + x.substring(1)
    case x => x
  }
  def scalaFieldTypeName(field: FieldDef[Type]) = {
    val itemTypeName =
      if (field.type_.isComplexType) scalaComplexTypeName(field.type_)
      else scalaSimpleTypeName(field.type_)
    if (field.isCollection) scalaCollectionTypeName(itemTypeName)
    else itemTypeName
  }
  def scalaCollectionTypeName(itemTypeName: String) = s"List[$itemTypeName]"
  // TODO generic, extract
  def scalaSimpleTypeName(t: Type) = t.name match {
    case "integer" => "BigInt"
    case "long" => "java.lang.Long"
    case "int" => "java.lang.Integer"
    case "decimal" => "BigDecimal"
    case "date" => "java.sql.Date"
    case "dateTime" => "java.sql.Timestamp"
    case "string" => "String"
    case "boolean" => "java.lang.Boolean"
    case "base64Binary" => "Array[Byte]"
    case "anyType" => "Any"
    case x =>
      throw new RuntimeException("Unexpected type: " + t)
  }
  def scalaComplexTypeName(t: Type) = scalaClassName(t.name)
  def initialValueString(col: FieldDef[Type]) =
    if (col.isCollection) "Nil" else "null"
  private def scalaFieldString(fieldName: String, col: FieldDef[Type]) =
    s"var $fieldName: ${scalaFieldTypeName(col)} = ${initialValueString(col)}"
  def scalaClassExtends(typeDef: ViewDef[FieldDef[Type]]) =
    Option(typeDef.extends_).filter(_ != "").map(scalaClassName)
  def scalaClassTraits(typeDef: ViewDef[FieldDef[Type]]): Seq[String] = Seq()
  def scalaFieldsIndent = "  "
  def scalaFieldsStrings(typeDef: ViewDef[FieldDef[Type]]) =
    typeDef.fields.map(f => scalaFieldString(
      scalaFieldName(Option(f.alias) getOrElse f.name), f))
  def createScalaClassString(typeDef: ViewDef[FieldDef[Type]]) = {
    val fieldsString = scalaFieldsStrings(typeDef)
      .map(scalaFieldsIndent + _ + nl).mkString
    val extendsString = Option(scalaClassTraits(typeDef))
      .map(scalaClassExtends(typeDef).toList ::: _.toList)
      .map(t => t.filter(_ != null).filter(_.trim != ""))
      .filter(_.size > 0)
      .map(_.mkString(" extends ", " with ", ""))
      .getOrElse("")
    s"class ${scalaClassName(typeDef.name)}$extendsString {$nl$fieldsString}"
  }
  def createScalaClassesString(
    headers: Seq[String], typedefs: Seq[ViewDef[FieldDef[Type]]], footers: Seq[String]) =
    List(headers, typedefs map createScalaClassString, footers)
      .flatMap(x => x)
      .mkString("", nl, nl)
}

object ScalaClassWriter extends ScalaClassWriter
