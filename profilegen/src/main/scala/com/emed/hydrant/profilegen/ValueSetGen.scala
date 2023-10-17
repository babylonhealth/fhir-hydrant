package com.emed.hydrant.profilegen

import cats.implicits.*
import com.emed.hydrant
import com.emed.hydrant.{
  ChildTemplate,
  Definitions,
  EnumDefinition,
  EnumDefinitionSystemError,
  EnumDefinitionValueError,
  Hydration,
  HydrationDefinition,
  RootInfo,
  Template,
  TemplateJson,
  TemplateString
}
import com.babylonhealth.lit.core.*
import com.babylonhealth.lit.core.model.{ CodeableConcept, Coding }
import com.babylonhealth.lit.hl7.*
import com.babylonhealth.lit.hl7.model.ValueSet
import io.circe.Json
import io.circe.JsonObject
import com.emed.hydrant.PathMethods.*
import com.babylonhealth.lit.core.{
  Canonical,
  CompanionFor,
  DecoderParams,
  FHIRComponentFieldMeta,
  FHIRObject,
  NonEmptyLitSeq,
  UriStr,
  Utils,
  toUri
}

import java.time.ZonedDateTime

trait ValueSetGen {

  def definitions: Definitions
  def paramNameByType: Map[String, String]
  val idProvider: IdProvider
  val structureDefinitions: StructureDefinitions
  def hydrate: Hydration

  /** There is difference between template/proto enums which can be used to codify any finite selection of options and fhir ValueSets which
    * are a set of codes taken from fhir CodeSystems. Therefore a ValueSet can only be created for an EnumDefinition that produces codes.
    */
  def makeValueSets(vsId: String, `enum`: EnumDefinition, date: ZonedDateTime): ValueSet = {
    val values = `enum`.values.map(_.value) ++ `enum`.default

    // Gather all codes by the system they are in
    val conceptsIncludes: Map[String, List[ValueSet.Compose.Include.Concept]] =
      values.distinct
        .flatMap(value => concepts(`enum`.id, `enum`.system.map(toUri), value))
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2))
        .toMap

    ValueSet(
      id = Some(vsId),
      url = Some(idProvider.valueSetUrl(`enum`)),
      title = Some(`enum`.name),
      description = Some(`enum`.description),
      // Name either from paramName, or drop bbl and domain from id
      name = Some(paramNameByType.getOrElse(`enum`.id, idProvider.valueSetNameFromIdAndDomain(vsId, `enum`.domain))),
      status = PUBLICATION_STATUS.ACTIVE,
      date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
      compose = Some(
        ValueSet.Compose(
          include = new NonEmptyLitSeq(conceptsIncludes.iterator.toList.map { case (system, concepts) =>
            ValueSet.Compose.Include(
              system = Some(system),
              concept = LitSeq.from(concepts)
            )
          })
        )
      )
    )
  }

  private def isParamTypeContains(paramName: String, template: Template, desireType: String)(pathFn: String => String): Boolean =
    template.resourceType
      .exists(r => structureDefinitions.typesOf(pathFn(s"$r.${template.hydrated.templateTokenPath(paramName)}")).contains(desireType))

  protected def isEnumConvertableParam(paramName: String, template: Template): Boolean =
    // Currently only support coding to enum
    isParamTypeContains(paramName, template, "Coding")(_.dropLastToken)

  private def isParamTypeCode(paramName: String, template: Template): Boolean =
    isParamTypeContains(paramName, template, "code")(identity)
  private def isParamTypeCoding(paramName: String, template: Template): Boolean =
    isParamTypeContains(paramName, template, "Coding")(identity)
  private def isParamTypeCodeableConcept(paramName: String, template: Template): Boolean =
    isParamTypeContains(paramName, template, "CodeableConcept")(identity)

  private def getHydratedFromJsonObj(
      itemKey: String,
      paramImpls: List[(String, Option[String])],
      template: TemplateJson.Obj): Option[TemplateString] =
    // paramImpls: paramName -> Some(implement)
    for {
      value    <- template(itemKey)
      valueStr <- value.asString
    } yield paramImpls.foldLeft(valueStr)((str, param) => param._2.map(impl => str.substitute(param._1, impl)).getOrElse(str))

  /** If the template is a parent template, and the Coding part (i.e code.coding.code/display) is defined in child template, then a valueset
    * with system/code/display is extracted from the parent/children templates and generated. The valueset id will be the
    * templateid+paramNameOfCodeField, where the paramNameOfCodeField should be the abstract field in parent template, whose fhirpath should
    * pointing to coding.code type.
    */
  def makeChildTemplateCodingValueSets(
      template: Template,
      definitions: Definitions,
      date: ZonedDateTime,
      groupName: Option[String] = None): Seq[ValueSet] = {
    val kids = definitions.all.collect {
      case childTemplate: ChildTemplate if childTemplate.`extends` == template.id && groupName.forall(childTemplate.group.contains) =>
        childTemplate
    }

    if (kids.isEmpty) Nil
    else {
      val codeValueSets = template.params
        .collect {
          case (paramName, paramInfo)
              if isParamAbstractWithImpls(paramName, paramInfo, template.id, kids, groupName) && isEnumConvertableParam(
                paramName,
                template) =>
            template.hydrated.templateTokenParentObj(paramName) -> paramName
        }
        .groupMap(_._1)(_._2) // (parentJson -> paramName)
        .toVector
        .flatMap {
          case (Some(jsonobj), paramNames) =>
            paramNames.collectFirst { case p if isParamTypeCode(p, template) => p }.flatMap { paramName =>
              val codings = kids
                .collect {
                  case childTemplate if paramNames.forall(paramName => childTemplate.implementMap.contains(paramName)) =>
                    val paramImpls = paramNames.map(p => p -> childTemplate.implementMap.get(p).flatMap(_.asString))
                    val system     = getHydratedFromJsonObj("system", paramImpls, jsonobj).map(_.asLiteralStringOrDie)
                    val code       = getHydratedFromJsonObj("code", paramImpls, jsonobj).map(_.asLiteralStringOrDie)
                    val display    = getHydratedFromJsonObj("display", paramImpls, jsonobj).map(_.asLiteralStringOrDie)
                    val version    = getHydratedFromJsonObj("version", paramImpls, jsonobj).map(_.asLiteralStringOrDie)
                    (system, version, code, display)
                }

              codingsToValueSet(template, date, groupName, paramName)(codings.toSet)
            }
          case _ => List.empty
        }

      val codingValueSets = template.params.collect {
        case (paramName, _) if isParamTypeCoding(paramName, template) =>
          val codings = kids.flatMap { kid =>
            getHydratedParam(template, kid.implement, paramName).map { implementedCoding =>
              (
                implementedCoding("system").flatMap(_.asLiteralString.toOption),
                implementedCoding("version").flatMap(_.asLiteralString.toOption),
                implementedCoding("code").flatMap(_.asLiteralString.toOption),
                implementedCoding("display").flatMap(_.asLiteralString.toOption))
            }
          }.toSet
          codingsToValueSet(template, date, groupName, paramName)(codings)
      }.flatten

      val codeableConceptValueSets = template.params.collect {
        case (paramName, _) if isParamTypeCodeableConcept(paramName, template) =>
          val codings = for {
            kid         <- kids
            ccJson      <- getHydratedParam(template, kid.implement, paramName)
            codingsJson <- ccJson("coding").toSeq
            codings     <- codingsJson.asArray.toSeq
            coding      <- codings
          } yield (
            coding("system").flatMap(_.asLiteralString.toOption),
            coding("version").flatMap(_.asLiteralString.toOption),
            coding("code").flatMap(_.asLiteralString.toOption),
            coding("display").flatMap(_.asLiteralString.toOption)
          )

          codingsToValueSet(template, date, groupName, paramName)(codings.toSet)
      }.flatten

      codeValueSets ++ codingValueSets ++ codeableConceptValueSets
    }
  }

  private def getHydratedParam(template: Template, implement: List[(String, Json)], paramName: String) = template.resourceType
    .map { rt =>
      val implementObj = JsonObject.fromIterable(implement)
      val semiHydrated =
        hydrate.hydrateParams(template, rt, partial = true)(implementObj)(template.params)(using RootInfo(template, implementObj)).toTry.get
      val path = template.hydrated.templateTokenPath(paramName)
      semiHydrated.current.followPath(path)
    }
    .getOrElse(Vector.empty)
    .flatMap(j => j.asArray.getOrElse(Vector(j)))

  private def codingsToValueSet(template: Template, date: ZonedDateTime, groupName: Option[String] = None, paramName: String)(
      codings: Set[(Option[String], Option[String], Option[String], Option[String])]) = { // (system, version, code, display)
    val id = idProvider.getChildTemplateCodingValueSetId(template, paramName, groupName)

    val includes = codings
      .groupMap(i => i._1 -> i._2)(i => i._3 -> i._4) // (system, version) -> List((code, display))
      .toVector
      .map { case ((system, version), concepts) =>
        ValueSet.Compose.Include(
          system = system.map(toUri),
          version = version,
          concept = LitSeq.from(concepts.flatMap { case (code, display) =>
            code.map(codeStr => ValueSet.Compose.Include.Concept(code = codeStr, display = display))
          })
        )
      }

    Option.when(includes.nonEmpty) {
      ValueSet(
        meta = idProvider.valueSetMeta,
        id = Some(idProvider.transformId(id)),
        url = Some(idProvider.valueSetUrlFromId(id)),
        name = Some( // Name either from group + paramName, or just from paramName
          groupName
            .map(_.capitalize + paramName.capitalize)
            .getOrElse(paramName.capitalize)),
        title = Some(s"${template.name} ${paramName} Enum" + groupName.map(" for group: " + _).getOrElse("")),
        description = Some(s"The valueset for ${template.name} ${paramName}" + groupName.map(" of group: " + _).getOrElse("")),
        status = PUBLICATION_STATUS.ACTIVE,
        date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
        compose = Some(
          ValueSet.Compose(
            include = LitSeq.from(includes).asNonEmpty
          )
        )
      )
    }
  }

  private def getCodings(value: Json): Either[Throwable, List[Coding]] = {
    given DecoderParams =
      DecoderParams(tolerateProfileErrors = false, flexibleCardinality = false, ignoreUnknownFields = false)
    Coding.baseType.decoder.decodeJson(value).toTry.toEither.map(List(_)) orElse CodeableConcept.baseType.decoder
      .decodeJson(value)
      .map(_.coding.toList)
  }

  def concepts(enumId: String, enumSystem: Option[String], value: Json): List[(String, ValueSet.Compose.Include.Concept)] = {
    if (value.isString)
      List((enumSystem.getOrElse(throw EnumDefinitionSystemError(enumId)), ValueSet.Compose.Include.Concept(code = value.asString.get)))
    else {
      val codings = getCodings(value)
      codings.toOption
        .toRight(EnumDefinitionValueError(enumId))
        .flatMap { codings =>
          codings.traverse { coding =>
            for {
              system <- coding.system.orElse(enumSystem).toRight(EnumDefinitionSystemError(enumId))
              concept <- coding.code
                .map(code => ValueSet.Compose.Include.Concept(code = code, display = coding.display))
                .toRight(EnumDefinitionValueError(enumId))
            } yield (system, concept)
          }
        }
        .toTry
        .get
    }
  }

}
