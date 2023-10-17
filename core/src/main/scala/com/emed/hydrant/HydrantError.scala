package com.emed.hydrant

import cats.implicits.*
import io.circe.Json

sealed abstract class HydrantError(val message: String) extends Exception(message)
case class NoResourceTypeError(templateId: String)      extends HydrantError(s"No resourceType ($templateId)")
case class InvalidParamsError(templateId: String)       extends HydrantError(s"Params should be an object ($templateId)")
sealed trait InputOrSchemaError

sealed abstract class HydrationError(innerMessage: String)(using info: HydrateInfo) extends HydrantError(s"$innerMessage ${info.print}")
case class WrappingHydrationError(error: HydrantError)(using info: HydrateInfo)     extends HydrationError(error.message)
case class RedactObjectError()(using info: HydrateInfo) extends HydrationError(s"Somehow redacted entire object") with InputOrSchemaError
case class MissingMandatoryValueError()(using info: HydrateInfo)
    extends HydrationError(s"Mandatory value not supplied")
    with InputOrSchemaError
case class AddObjectError()(using info: HydrateInfo) extends HydrationError(s"Somehow adding to entire object") with InputOrSchemaError
case class UnknownComplexTypeError(typeId: String)
    extends HydrantError({
      val primitiveTypes =
        if (typeId == typeId.toLowerCase)
          "\nValid primitive types are: boolean, integer, int64, string, decimal, uri, url, canonical, base64Binary, instant, date, dateTime, time, code, oid, id, markdown, unsignedInt, positiveInt, uuid"
        else ""
      s"Unknown type: $typeId$primitiveTypes"
    })
    with InputOrSchemaError
case class NoReferenceIdError()(using info: HydrateInfo) extends HydrationError(s"No id for inline reference") with InputOrSchemaError
case class UnrecognisedParam(paramName: String, templateId: String)
    extends HydrantError(s"Unrecognised template param $paramName in template $templateId")
    with InputOrSchemaError
case class MissingParam(paramName: String)
    extends HydrantError(s"Dehydrated FHIR is missing template param $paramName")
    with InputOrSchemaError
case class ExpectedArray(paramName: String)
    extends HydrantError(s"Dehydrated FHIR should be an array because of repeated param $paramName")
    with InputOrSchemaError
case class ExpectedChildTemplateEnum(templateId: String)
    extends HydrantError(s"expecting child template enum but not provided on ($templateId)")
    with InputOrSchemaError
case class ParamCardinalityError(paramInfo: ParamInfo)(using info: HydrateInfo)
    extends HydrationError(s"Invalid cardinality of param ${paramInfo.fhirMin}..${paramInfo.fhirMax}")
    with InputOrSchemaError
case class TemplateStringInputError()(using info: HydrateInfo)
    extends HydrationError(s"Template strings only work for string inputs")
    with InputOrSchemaError
case class RemainingTemplateValuesError(templateId: Option[String], remaining: Set[String])
    extends HydrantError(s"Remaining template values: ${remaining.mkString(", ")}${templateId.fold("")(id => s" ($id)")}")
    with InputOrSchemaError
case class MultipleInputFieldsError()(using info: HydrateInfo)
    extends HydrationError(s"Not allowed multiple input fields in the same repeated objects, create a new type instead")
    with InputOrSchemaError
case class InvalidEnumValue(`enum`: EnumDefinition, name: String)(using info: HydrateInfo)
    extends HydrationError(
      s"Invalid value for enum ${`enum`.id}: $name ${info.print}, should be one of ${`enum`.validNames.flatten.mkString(", ")}")
    with InputOrSchemaError
case class NonOptionalEnumWithNoDefault(`enum`: EnumDefinition, name: String)(using info: HydrateInfo)
    extends HydrationError(s"Invalid value for enum ${`enum`.id}: $name ${info.print}, if absent a default needs to be defined")
    with InputOrSchemaError
case class ExpectedEnum(typeName: String)(using info: HydrateInfo)
    extends HydrationError(s"Complex type $typeName used in a format string, only enum types are allowed:")
    with InputOrSchemaError

case class ExpectedTemplate(typeName: String)
    extends HydrantError(s"Complex type $typeName is expected a template but is not")
    with InputOrSchemaError
case class EnumDefinitionNameError(enumId: String)
    extends HydrantError(s"Enum $enumId is not valid as the enum fields do not have either a name field or a string value.")
    with InputOrSchemaError

case class DehydrateEmptyArrayError(templateId: String) extends HydrantError(s"Cannot dehydrate empty array ($templateId)")
case class DehydratedMissingRequiredFieldsError(templateId: String, missingDfhir: List[String])
    extends HydrantError(s"Required dfhir fields were missing: ${missingDfhir.mkString(", ")} ($templateId)")
case class DehydrateFixedValueMismatch(templateId: String, unmatchPath: String, error: String)
    extends HydrantError(s"Mismatched fixed values at $unmatchPath - $error ($templateId)")
case class NoMatchingChildTemplateError(templateId: String, params: String, keys: String)
    extends HydrantError(s"Couldn't dehydrate: $templateId has no child template with params $params.\nValid child templates:\n$keys")
object NoMatchingChildTemplateError {
  def apply(templateId: String, childTemplatesMap: Map[(String, Json), String], childImplementJson: Json): NoMatchingChildTemplateError = {
    val keys =
      childTemplatesMap
        .collect { case ((`templateId`, params), child) => s"$child: ${params.spaces2SortKeys}" }
        .mkString(
          "\n"
        )
    NoMatchingChildTemplateError(templateId, childImplementJson.spaces2SortKeys, keys)
  }
}
case class InvalidEnumValueError(enumDefn: EnumDefinition, fhir: Json)
    extends HydrantError(
      s"Invalid enum value: ${fhir.noSpaces}, does not map to dehydrated fhir enum ${enumDefn.id} - possible values are: [${enumDefn.values
          .map(_.value.noSpaces)
          .mkString(", ")}]")

case class UnexpectedReference(defnId: String, path: String) extends HydrantError(s"Unexpected object reference type at $path ($defnId)")
case class UnableToFindReference(defnId: String, path: String)
    extends HydrantError(s"Error in getting the reference with path: $path ($defnId)")
case class DehydrateObjectToPrimitiveError(paramName: String) extends HydrantError(s"Only complex types can be objects $paramName")
case class DehydrateTemplateExpectsStringError(str: String)
    extends HydrantError(s"FHIR value didn't match template. Expected a matching string $str - but didnt get a string")
case class DehydrateTemplateStringMismatchError(str: String)
    extends HydrantError(s"FHIR value didn't match template. Got a string but it didn't match $str")
case class DehydrateStringUsingTemplateError(paramName: String, paramInfo: ParamInfo)
    extends HydrantError(s"Tried to extract a string for $paramName as complex type ${paramInfo.`type`} - but this only works for enums")
case class DehydrateExpectedArrayError(templateId: String, path: String)
    extends HydrantError(s"Template $templateId has array at $path but given FHIR did not contain an array there.")
case class DehydrateExpectedObjectError(templateId: String, path: String)
    extends HydrantError(s"Template $templateId has object at $path but given FHIR did not contain an object there.")
case class UnableToDehydrateAllValuesError(templateId: String, path: String)
    extends HydrantError(s"Unable to dehydrate all array values into template $templateId at $path.")
case class UnableToDehydrateAllFhirResourcesError(templateId: String, unused: Set[String])
    extends HydrantError(s"Unable to dehydrate all fhir resources into template $templateId: [${unused.mkString(", ")}]")
object UnableToDehydrateAllFhirResourcesError {
  def apply(templateId: String, fhirs: Set[Json], usedFhirs: Set[Json]): UnableToDehydrateAllFhirResourcesError = {
    val unused = (fhirs -- usedFhirs).flatMap {
      _.asObject.map(o => s"(id: ${o("id").getOrElse("None")}, resourceType: ${o("resourceType").getOrElse("None")})")
    }
    UnableToDehydrateAllFhirResourcesError(templateId, unused)
  }
}
case class MoreThanOneValueForNonRepeatedFieldError(paramName: String, templateId: String)
    extends HydrantError(s"Found more than one value for non-repeated field $paramName in template $templateId")

case class ChildTemplateDehydrationNotSupportedError(childTemplate: ChildTemplate)
    extends HydrantError(
      s"Dehydration using child template (${childTemplate.id}) is not supported. Please use parent template (${childTemplate.`extends`}) for dehydration")

case object MatchError extends HydrantError(s"Some code didnt match a pattern. Likely a bug in Hydrant.")

case class EnumDefinitionValueError(enumId: String)
    extends Error(s"Cannot create ValueSet for enum $enumId. Enum values must be strings or fhir codes")
    with InputOrSchemaError
case class EnumDefinitionSystemError(enumId: String)
    extends Error(s"Cannot create ValueSet for enum $enumId. An enum must have a system or fhir code values with a system field.")
    with InputOrSchemaError

object HydrantError {
  type ErrorOr[X] = Either[HydrantError, X]

  extension [T](option: Option[T])
    def orError[E <: HydrantError](error: => E): Either[E, T] =
      Either.fromOption(option, error)

  extension [X](error: ErrorOr[X]) def withFilter(p: X => Boolean): ErrorOr[X] = error.flatMap(e => on(!p(e), MatchError) as e)

  def on(cond: => Boolean, error: => HydrantError): ErrorOr[Unit] = Either.cond(!cond, (), error)
}
