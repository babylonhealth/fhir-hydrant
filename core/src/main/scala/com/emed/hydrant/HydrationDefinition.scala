package com.emed.hydrant

import cats.implicits.*
import com.emed.hydrant.HydrantError.*
import com.emed.hydrant.ParamType.*
import com.emed.hydrant.PrimitiveParamType.*
import com.emed.hydrant.TemplateJson.{ Arr, Obj }
import io.circe.{ Decoder, Json, JsonObject }
import io.circe.generic.auto.*

import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.Try

sealed trait HydrationDefinition {
  val id: String
  val name: String
  val version: Option[String]
  val domain: String
  val packageName: Option[String]
  val description: String
  val resourceType: Option[String] = None

  def isAbstract: Boolean
  def isObjectReference(path: String)(using typeProvider: FhirTypeProvider): Option[String] = None

  def packageNameOrDomain = packageName getOrElse domain

  def enumBaseName: String = {
    if (id.toLowerCase startsWith packageNameOrDomain.toLowerCase)
      id.capitalize
    else
      s"${packageNameOrDomain.capitalize}${id.capitalize}"
  }

  def asEnumDefinition: Option[EnumDefinition] =
    this match {
      case t: EnumDefinition => Some(t)
      case _                 => None
    }

  def asTemplate: Option[Template] =
    this match {
      case t: Template => Some(t)
      case _           => None
    }

  def asChildTemplate: Option[ChildTemplate] =
    this match {
      case t: ChildTemplate => Some(t)
      case _                => None
    }
}

object HydrationDefinition {
  val referenceTypes: Set[Set[String]] = Set(Set("Reference"), Set("canonical"))
  def allSubDefns(definitions: Definitions, includeOtherResources: Boolean = true)(defn: HydrationDefinition): Set[HydrationDefinition] =
    defn match {
      case t: Template if includeOtherResources || t.resourceType.isDefined =>
        t.params.toSet[(String, ParamInfo)].flatMap {
          case (paramName, info) if info.isComplexType && Template.isFullTemplatedValue(paramName, t.hydrated) =>
            Set(definitions(info.`type`).toTry.get).flatMap(allSubDefns(definitions)) + defn
          case _ => Set(defn)
        }
      case _: HydrationDefinition => Set(defn)
      case null                   => Set.empty
    }

  def allSubDefnPaths(definitions: Definitions, path: String = "")(defn: HydrationDefinition): Map[String, String] =
    defn match {
      case t: Template =>
        t.params.toMap.flatMap {
          case (paramName, info)
              if info.isComplexType &&
                Template.isFullTemplatedValue(paramName, t.hydrated) &&
                (path.isEmpty || t.resourceType.isEmpty) =>
            definitions(info.`type`).toOption.toList
              .flatMap(defn =>
                allSubDefnPaths(
                  definitions,
                  s"${t.resourceType.getOrElse(path)}.${t.hydrated.templateTokenPath(paramName)}"
                )(defn).toList)
              .toMap
          case (paramName, _) =>
            Map(defn.id -> s"${t.resourceType.getOrElse(path)}.${t.hydrated.templateTokenPath(paramName)}")
          case null => Map.empty
        } ++ Map(defn.id -> t.resourceType.getOrElse(path))
      case _: HydrationDefinition => Map(defn.id -> path)
      case null                   => Map.empty
    }
}

case class EnumDefinition(
    id: String,
    name: String,
    version: Option[String],
    domain: String,
    packageName: Option[String],
    description: String,
    `abstract`: Option[Boolean],
    values: List[EnumDefinitionValue],
    system: Option[String],
    url: Option[String],
    default: Option[Json], // Must be defined when used for a field that has min > 0
    fhirType: Option[PrimitiveParamType],
    absentName: Option[String],
    allowAbsent: Option[Boolean]
) extends HydrationDefinition {

  // These values need to be lazy as the package name is not set as part of initialisation
  lazy val defaultEnumPrefix: String = camelToUnderscores(enumBaseName)
  // `None` indicates the "ABSENT" value.
  lazy val valueByName: Map[Option[String], Json] =
    default.toSeq.flatMap(d => Seq(None -> d, Some(absentNameFromName) -> d)).toMap ++ nameValuePairs
  lazy val nameByValue: Map[Json, Option[String]] =
    default.map(_ -> None).toMap + (Json.Null -> None) ++ nameValuePairs.map(_.swap)
  lazy val validNames: Iterable[Option[String]] = nameValuePairs.keys ++ default.as(None)

  private lazy val nameValuePairs: Map[Option[String], Json] =
    values.map(v => Some(valueName(v)) -> addSystemToValue(v.value, system)).toMap

  def valueName(value: EnumDefinitionValue) = value.nameOrDefault(defaultEnumPrefix)

  def addSystemToValue(value: Json, system: Option[String]): Json = {
    system.map(addSystemToValue(value, _)).getOrElse(value)
  }

  def addSystemToValue(value: Json, system: String): Json = {
    value.asObject
      .map(obj =>
        value.as[Coding] match {
          case Left(_) => obj
          case Right(coding) =>
            coding.code
              .map(_ => obj.add("system", obj("system").getOrElse(Json.fromString(system))))
              .getOrElse(obj)
        })
      .map(Json.fromJsonObject)
      .getOrElse(value)
  }

  def absentNameFromName: String = absentName.getOrElse(EnumDefinition.enumNameString(defaultEnumPrefix) + "_ABSENT")

  val fhirTypeOrDefault: PrimitiveParamType = fhirType.getOrElse(code)

  override def isAbstract: Boolean = `abstract`.getOrElse(false)

  override def isObjectReference(path: String)(using typeProvider: FhirTypeProvider): Option[String] = {
    val types = typeProvider.typesOf(path)
    Option
      .when(HydrationDefinition.referenceTypes.contains(types) && values.forall(_.value.asObject.exists(_.contains("resourceType"))))(
        types.headOption
      )
      .flatten
  }
}

object EnumDefinition {

  def enumNameString(name: String): String = name.toUpperCase.replace(' ', '_').replace('-', '_').replaceAll("__", "_")

}

case class EnumDefinitionValue(
    name: Option[String],
    value: Json,
    description: Option[String]
) {
  def nameOrDefault(prefix: String): String =
    name.getOrElse(EnumDefinition.enumNameString(prefix + "_" + value.asString.getOrElse(throw EnumDefinitionNameError(prefix))))
}

case class ChildTemplate(
    id: String,
    name: String,
    version: Option[String],
    domain: String,
    packageName: Option[String],
    description: String,
    `extends`: String,
    default: Option[Boolean],
    order: Option[Int],
    implement: List[(String, Json)],
    group: Option[String]
) extends HydrationDefinition {
  override def isAbstract: Boolean         = false
  def isDefault: Boolean                   = default.getOrElse(false)
  lazy val implementMap: Map[String, Json] = implement.toMap

  /** Fill the parent template with values in child template, and generate an complete template on child.
    *
    * @return
    */
  def fillParentTemplate(hydrate: Hydration): ErrorOr[Template] = {
    for {
      parent       <- hydrate.definitions(`extends`)
      template     <- parent.asTemplate.orError(ExpectedTemplate(parent.name))
      resourceType <- template.resourceType.orError(NoResourceTypeError(parent.id))
      hydratedObj = JsonObject.fromIterable(implement)
      info        = RootInfo(template, hydratedObj)
      newHydrated <- hydrate.hydrateParams(template, resourceType)(hydratedObj)(template.params.filter { case (_, paramInfo) =>
        paramInfo.isAbstract
      })(using info)
    } yield template.copy(
      id = id,
      name = name,
      description = description,
      domain = domain,
      params = template.params.filterNot { case (_, paramInfo) => paramInfo.isAbstract },
      hydrated = newHydrated.current
    )
  }
}

case class Template(
    id: String,
    name: String,
    version: Option[String],
    domain: String,
    params: List[
      (String, ParamInfo)
    ], // Decodes key -> value into a list instead of Map to preserve order - see orderedMapDecoder
    hydrated: TemplateJson,
    packageName: Option[String],
    description: String,
    `abstract`: Option[Boolean],
    baseDefinition: Option[String],
    profileId: Option[String],
    childTypeFieldNumber: Option[Int] = None,
    containedResource: Option[Boolean] = None // If true disables tests that will fail for contained resources
) extends HydrationDefinition {
  val paramByName: Map[String, ParamInfo] = params.toMap
  def flattenParamByName(definitions: Definitions): Map[String, ParamInfo] = params.flatMap {
    case (_, param) if param.isFlattened =>
      definitions(param.`type`).toOption
        .collect { case t: Template =>
          t.params
        }
        .getOrElse(throw UnknownComplexTypeError(param.`type`.entryName))
    case other => List(other)
  }.toMap

  override def isAbstract: Boolean = `abstract`.getOrElse(false)
  override val resourceType        = hydrated("resourceType").flatMap(_.asLiteral.toOption).flatMap(_.asString)
  def isParent: Boolean            = params.exists(_._2.isAbstract)
  lazy val sortedParams            = params.sortBy(_._2.isAbstract) // Put abstract fields as last to dehydrate
  override def isObjectReference(path: String)(using typeProvider: FhirTypeProvider): Option[String] = {
    val types = typeProvider.typesOf(path)
    Option.when(HydrationDefinition.referenceTypes.contains(types) && hydrated("resourceType").isDefined)(types.headOption).flatten
  }
}

object Template {

  /** Is the id present in the json as a full template token * */
  def isFullTemplatedValue(paramName: String, json: TemplateJson): Boolean = json match {
    case TemplateString.token(`paramName`) => true
    case _: TemplateJson.Primitive         => false
    case Arr(arr)                          => arr.exists(isFullTemplatedValue(paramName, _))
    case Obj(obj)                          => obj.values.exists(isFullTemplatedValue(paramName, _))
  }
}

case class ParamInfo(
    `type`: ParamType,
    description: String,
    repeated: Option[Boolean] = None,
    optional: Option[Boolean] = None,
    propagate: Option[Boolean] = None,
    provided: Option[Boolean] = None,
    flatten: Option[Boolean] = None,
    contained: Option[Boolean] = None,
    tags: Option[Map[String, Json]] = None,
    `abstract`: Option[Boolean] = None,
    deprecated: Option[Boolean] = None,
    valueSet: Option[Json] = None,
    index: Option[Int] = None
) {
  def shouldPropagate = propagate getOrElse true
  def isProvided      = provided getOrElse false
  def isFlattened     = flatten getOrElse false
  def isContained     = contained getOrElse false
  def isRepeated      = repeated getOrElse false
  def isOptional      = (optional getOrElse false) || isRepeated
  def fhirMax         = if (isRepeated) "*" else "1"
  def fhirMin         = if (isOptional) 0 else 1
  def isComplexType   = `type`.isInstanceOf[ComplexType]
  def isAbstract      = `abstract` getOrElse false
  def isDeprecated    = deprecated getOrElse false
}
