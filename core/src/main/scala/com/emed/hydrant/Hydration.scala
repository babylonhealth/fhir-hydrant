package com.emed.hydrant

import cats.data.WriterT
import cats.implicits.*
import com.emed.hydrant.*
import com.emed.hydrant.HydrantError.*
import com.emed.hydrant.HydrationDefinition.*
import com.emed.hydrant.TemplateJson.Obj
import io.circe.syntax.*
import PathMethods.*
import com.emed.hydrant.TemplateJson.Obj
import io.circe.{ Json, JsonObject }

/** Used for hydrating dFHIR into FHIR
  * @param definitions
  *   Definitions class holding all of the HydrationDefinitions
  * @param redactObjects
  *   Jsons which should be redacted if present the final hydrated FHIR. E.g. Can be used if you do not want Codings with no code but only a
  *   system.
  */
class Hydration(val definitions: Definitions, redactObjects: Set[TemplateJson.Obj] = Set.empty)(using
    typeProvider: FhirTypeProvider,
    referenceProvider: ReferenceProvider) {

  lazy val defaultChildTemplateId: Map[String, String] = definitions.all.collect {
    case v: ChildTemplate if v.isDefault => v.`extends` -> v.id
  }.toMap

  def hydrateUnsafe(template: Template, dehydrated: Json): Json =
    hydrateJson(template)(dehydrated).fold(throw _, identity)

  def hydrateJson(template: Template)(dehydrated: Json): ErrorOr[Json] = for {
    dehydratedObj <- dehydratedFromJson(template)(dehydrated)
    hydrated <- hydrate(template)(dehydratedObj).map {
      case Seq(hydr: HydratedSingleResource) => hydr.current
      case hydrated                          => Json.fromValues(hydrated.map(_.current))
    }
  } yield hydrated

  def hydrate(template: Template)(dehydrated: JsonObject): ErrorOr[Vector[HydratedSingleResource]] = {
    given HydrateInfo = RootInfo(template, dehydrated)

    template.hydrated.asArray
      .map(hydrateListTemplate(template, dehydrated))
      .getOrElse {
        for {
          resourceType <- template.resourceType.orError(NoResourceTypeError(template.id))
          hydrated     <- doHydrate(template, resourceType)(dehydrated)
        } yield hydrated.makeSingleResourceList
      }
  }

  def hydrateListTemplate(template: Template, dehydrated: JsonObject)(hydrateElems: Vector[TemplateJson])(using
      HydrateInfo): ErrorOr[Vector[HydratedSingleResource]] =
    hydrateElems
      .flatTraverse { h =>
        val hydrated = h.asString.collect { case TemplateString.token(name) =>
          tryHydrateListElem(template, dehydrated)(name)
        }
        hydrated.getOrElse(hydrate(template.copy(hydrated = h))(dehydrated))
      }

  private def extractChildImplements(template: Template, dehydratedObj: JsonObject): ErrorOr[JsonObject] =
    if (template.isParent) {
      val childId = (for {
        enumValue <- dehydratedObj(childTemplateEnumKey)
        enumStr   <- enumValue.asString
      } yield childTemplateEnumValueToId(enumStr, template.id, template.enumBaseName)).orElse(defaultChildTemplateId.get(template.id))
      val implement = for {
        templateId    <- childId
        childtemplate <- definitions.iget(templateId)
        t             <- childtemplate.asChildTemplate
      } yield t.implement.foldLeft(dehydratedObj)((obj, t) => t +: obj)
      implement.orError(ExpectedChildTemplateEnum(template.id))
    } else Right(dehydratedObj)

  private def tryHydrateListElem(template: Template, dehydrated: JsonObject)(paramName: String)(using
      info: HydrateInfo): ErrorOr[Vector[HydratedSingleResource]] = {
    for {
      paramInfo <- template.paramByName.get(paramName).orError(UnrecognisedParam(paramName, template.id))
      innerDefn <- paramDefinition(using ChildInfo(paramName, paramInfo, template, JsonObject.empty, info))
      hydrated <- innerDefn.asTemplate
        .map { template =>
          for {
            dFhir <-
              if (paramInfo.isFlattened && paramInfo.isComplexType)
                Right(Vector(dehydrated))
              else {
                if (paramInfo.isRepeated)
                  dehydrated(paramName).map(_.asArray.orError(ExpectedArray(paramName))).getOrElse(Right(Vector.empty))
                else if (paramInfo.isOptional)
                  Right(dehydrated(paramName).toVector)
                else
                  dehydrated(paramName).orError(MissingParam(paramName)).map(Vector(_))
              }.flatMap(_.traverse(dehydratedFromJson(template)))
            propagated = dFhir.map(innerObjWithPropagations(template, dehydrated, _))
            result <- propagated.flatTraverse(hydrate(template))
          } yield result
        }
        .orError(ExpectedTemplate(paramInfo.`type`.entryName))
        .flatten
    } yield hydrated
  }

  def hydrateEnum(enumDefn: EnumDefinition)(name: String)(using info: ChildInfo): ErrorOr[Json] =
    if (name == enumDefn.absentNameFromName && !info.param.isOptional && enumDefn.default.isEmpty)
      Left(NonOptionalEnumWithNoDefault(enumDefn, name))
    else
      enumDefn.valueByName.get(Some(name)) orError InvalidEnumValue(enumDefn, name)

  private def liftFlattened(template: Template, dehydrated: JsonObject): JsonObject = {
    val unflattenedFields = for {
      (paramName, paramInfo) <- template.params
      if paramInfo.isFlattened
      if paramInfo.isComplexType
      defn <- definitions(paramInfo.`type`).toOption.collect { case t: Template => t }
    } yield (paramName, Json.fromJsonObject(dehydrated.filter(defn.paramByName.keySet contains _._1)))

    unflattenedFields.foldLeft(dehydrated) { case (o, (k, v)) => o.add(k, v) }
  }

  // In DynamicMessage, proto will omit absent enum fields, this function is to get the absent value on enum or raise error
  private def getEnumAbsentNameOrError(using ChildInfo): ErrorOr[Json] = {
    paramDefinition.flatMap {
      case enumDef: EnumDefinition => Right(Json.fromString(enumDef.absentNameFromName))
      case _                       => Left(MissingMandatoryValueError())
    }
  }

  def hydrateParams(template: Template, path: String, partial: Boolean = false)(dehydrated: JsonObject)(params: List[(String, ParamInfo)])(
      using parent: HydrateInfo): ErrorOr[PartHydrated] = {
    val dehydratedValues = liftFlattened(template, dehydrated)
    params.foldLeftM[ErrorOr, PartHydrated](PartHydrated(template.hydrated, None, Vector.empty)) {
      case (hydrated @ PartHydrated(partiallyFilled, _, currentResources), (paramName, paramInfo)) =>
        given info: ChildInfo = ChildInfo(paramName, paramInfo, template, dehydratedValues, parent)

        val paramValue = dehydratedValues(paramName)

        (paramInfo.isOptional, paramInfo.isRepeated, paramValue) match {
          case (true, _, None) =>
            redactParam(paramName)(partiallyFilled).orError(RedactObjectError()).map(PartHydrated(_, currentResources))
          case (false, _, None) if paramInfo.isComplexType =>
            getEnumAbsentNameOrError
              .flatMap(replaceParam(_, path)(partiallyFilled))
              .map(_.add(currentResources))
              .recover { case _ if partial => hydrated }
          case (false, _, None) if !partial => Left(MissingMandatoryValueError())
          case (_, false, Some(paramValue)) if !paramValue.isArray =>
            replaceParam(paramValue, path)(partiallyFilled).map(_.add(currentResources))
          case (_, true, Some(paramValue)) =>
            val values: Vector[Json] = paramValue.asArray getOrElse Vector(paramValue)
            values.zipWithIndex
              .foldLeftM(hydrated) { case (h, (paramValue, index)) =>
                for {
                  p <- addParam(paramValue, path)(h.current)(using info.copy(index = Some(index)))
                  (nextHydrated, toAdd) = p
                  _ <- HydrantError.on(toAdd, AddObjectError())
                } yield nextHydrated.add(h.otherResources)
              }
              .flatMap { h =>
                redactParam(paramName)(h.current)
                  .orError(RedactObjectError())
                  .map(redacted => h.copy(current = redacted)) // clean up remaining template values
              }

          case _ if !partial => Left(ParamCardinalityError(paramInfo))
          case _             => Right(hydrated)
        }
    }
  }

  def doHydrate(template: Template, path: String)(dehydrated: JsonObject)(using HydrateInfo): ErrorOr[Hydrated] =
    for {
      dehydratedComplete <- extractChildImplements(template, dehydrated)
      partReHydrated     <- hydrateParams(template, path)(dehydratedComplete)(template.params)
      reHydrated         <- partReHydrated.toFullyHydrated
    } yield {
      val hydrated = addIdentifier(template, reHydrated).addMeta(dehydrated = dehydrated, template = template)
      pruneEmptyExtensions(path.lastToken, hydrated.current).map(pruned => hydrated.copy(current = pruned)).getOrElse(hydrated)
    }

  private def addIdentifier(template: Template, hydrated: Hydrated)(implicit info: HydrateInfo): Hydrated = {
    if (info.isContained) {
      val pathId  = Json.fromString(info.path.mkString("."))
      val current = Json.obj("id" -> pathId) deepMerge hydrated.current
      hydrated.copy(current)
    } else {
      val withIdentifier = for {
        resourceType <- template.resourceType
        system       <- referenceProvider.identifierSystem(template)
        max          <- typeProvider.maxOf(s"$resourceType.identifier")
        obj          <- hydrated.current.asObject
        if obj("identifier").isEmpty
        idJson <- obj("id")
        id     <- idJson.asString
      } yield {
        val identifier = Json.obj("system" -> Json.fromString(system), "value" -> Json.fromString(id))
        hydrated.copy(
          current = Json.fromJsonObject(
            obj.add("identifier", if (max == "1") identifier else Json.arr(identifier))
          )
        )
      }

      withIdentifier getOrElse hydrated
    }
  }

  private def redactParam(paramName: String)(hydratedTemplate: TemplateJson)(using parent: HydrateInfo): Option[TemplateJson] = {
    val toRedact = TemplateString.token(paramName)
    hydratedTemplate.fold(
      None,
      TemplateJson.Bool(_).some,
      TemplateJson.Num(_).some,
      str => Option.when(!str.contains(toRedact))(str),
      arr => {
        val redacted = arr
          .flatMap(redactParam(paramName))
          .filter(_.arrayOrObject(true, _.nonEmpty, _.fields.nonEmpty))
        Option.when(redacted.nonEmpty)(TemplateJson.Arr(redacted))
      },
      obj => {
        val redacted = Obj.from(obj.fields.view.mapValues(redactParam(paramName)).collect {
          case (key, Some(value)) if value.arrayOrObject(true, _.nonEmpty, _.fields.nonEmpty) => key -> value
        })
        val shouldRedactWholeObject = redactObjects contains redacted
        Option.when(!shouldRedactWholeObject)(redacted)
      }
    )
  }

  private def propagatedParams(definition: HydrationDefinition, dehydrated: JsonObject): Map[String, Json] =
    definition match {
      case template: Template =>
        template.paramByName.flatMap { case (paramName, paramValue) =>
          Option.when(paramValue.shouldPropagate)(dehydrated(paramName).map(paramName -> _)).flatten
        }
      case _ => Map.empty
    }

  private def innerObjWithPropagations(t: Template, outerObj: JsonObject, innerObj: JsonObject) = JsonObject.fromIterable(
    propagatedParams(t, outerObj).view.filterKeys(t.paramByName.get(_).exists(_.isProvided)) ++ innerObj.toMap
  )

  private def replaceJson(paramValue: Json, path: String)(using info: ChildInfo): ErrorOr[Hydrated] = {

    def noChange = Right(Hydrated(paramValue))

    // given the definition of the param type, modify the dehydrated json and collected resources
    def replaceInner(handle: PartialFunction[HydrationDefinition, ErrorOr[Hydrated]]): ErrorOr[Hydrated] =
      if (info.param.isComplexType) {
        paramDefinition.flatMap(handle.lift(_).getOrElse(noChange))
      } else noChange

    def makeReference(defn: HydrationDefinition)(hydrated: Hydrated): ErrorOr[Hydrated] = {
      lazy val referenceType = defn.isObjectReference(path)
      hydrated match {
        case Hydrated(json, meta, otherResources) if referenceType.isDefined =>
          val referenceJson = for {
            uri <- uriFromResource(json)
          } yield referenceType
            .map {
              case "Reference" => Json.obj("reference" -> Json.fromString(uri))
              case "canonical" => Json.fromString(uri)
              case _           => json
            }
            .getOrElse(json)

          referenceJson
            .orError(NoReferenceIdError())
            .map(
              Hydrated(_, otherResources = HydratedSingleResource(json, meta, Some(info.param)) +: otherResources)
            )
        case h => Right(h)
      }
    }

    paramValue.asObject
      .map { paramValueObj =>
        replaceInner { case t: Template =>
          doHydrate(t, t.resourceType.getOrElse(path))(innerObjWithPropagations(t, info.dehydrated, paramValueObj))
            .flatMap(makeReference(t))
        }
      }
      .orElse {
        paramValue.asString.map { paramValueStr =>
          replaceInner { case enumDef: EnumDefinition =>
            hydrateEnum(enumDef)(paramValueStr).map(Hydrated(_)).flatMap(makeReference(enumDef))
          }
        }
      }
      .getOrElse(noChange)
  }

  private def hydratedEnumOrBasicType(value: Json)(using info: ChildInfo) =
    if (info.param.isComplexType) {
      paramDefinition
        .flatMap {
          case enumDef: EnumDefinition =>
            value.asString
              .orError(TemplateStringInputError())
              .flatMap(hydrateEnum(enumDef))
              .map(_.asString.getOrElse(""))
          case _ => Left(ExpectedEnum(info.param.`type`.entryName))
        }
    } else {
      value.fold(
        Left(TemplateStringInputError()),
        b => Right(b.toString),
        n => Right(n.toString),
        s => Right(s),
        _ => Left(TemplateStringInputError()),
        _ => Left(TemplateStringInputError())
      )
    }

  private def replaceParam(paramValue: Json, path: String)(template: TemplateJson)(using info: ChildInfo): ErrorOr[PartHydrated] = {
    val toReplace = TemplateString.token(info.paramName)
    template.fold(
      Right(PartHydrated(TemplateJson.Null)),
      b => Right(PartHydrated(TemplateJson.Bool(b))),
      n =>
        if (typeProvider.typesOf(path) contains "string")
          Right(PartHydrated(TemplateString.literal(n.toString)))
        else
          Right(PartHydrated(TemplateJson.Num(n))),
      str => {
        if (str == toReplace) replaceJson(paramValue, path).map(_.toPartHydrated)
        else if (str contains toReplace)
          hydratedEnumOrBasicType(paramValue).map(s => PartHydrated(str.substitute(info.paramName, s)))
        else Right(PartHydrated(str))
      },
      _.traverse(replaceParam(paramValue, path)).map { replaced =>
        val (jsons, outerElems) = replaced.map(_.toTuple).unzip
        PartHydrated(TemplateJson.Arr(jsons), outerElems.flatten)
      },
      _.fields.toVector
        .traverse { case (key, value) =>
          WriterT(replaceParam(paramValue, s"$path.$key")(value).map { case PartHydrated(json, _, otherResources) =>
            (otherResources, key -> json)
          })
        }
        .run
        .map { case (otherResources, current) =>
          PartHydrated(Obj.from(current), otherResources)
        }
    )
  }

  private def addParam(paramValue: Json, path: String)(template: TemplateJson)(using info: ChildInfo): ErrorOr[(PartHydrated, Boolean)] = {
    val toAdd = TemplateString.token(info.paramName)
    template.fold(
      Right(PartHydrated(TemplateJson.Null) -> false),
      b => Right(PartHydrated(TemplateJson.Bool(b)) -> false),
      n => Right(PartHydrated(TemplateJson.Num(n)) -> false),
      str =>
        if (str == toAdd)
          replaceJson(paramValue, path).map(h => h.toPartHydrated -> true)
        else if (str contains toAdd)
          paramValue.asString
            .orError(TemplateStringInputError())
            .map(str.substitute(info.paramName, _))
            .map(PartHydrated(_) -> true)
        else
          Right(PartHydrated(str) -> false),
      arr =>
        arr.traverse(addParam(paramValue, path)).map { added =>
          val (addParams, resources) = added.collect { case (h, true) => h.toTuple }.unzip
          PartHydrated(TemplateJson.Arr(arr ++ addParams), resources.flatten) -> false
        },
      _.fields.toList
        .traverse { case (key, value) =>
          addParam(paramValue, s"$path.$key")(value).map { case (PartHydrated(json, _, otherResources), toAdd) =>
            (key -> json, otherResources, toAdd)
          }
        }
        .flatMap { added =>
          val (addElems, resources, toAdds) = added.unzip3

          val newObj = Obj.from(addElems)
          val adds   = toAdds.contains(true)

          HydrantError
            .on(adds && newObj.hasRemainingTemplateParams, MultipleInputFieldsError())
            .as(PartHydrated(newObj, resources.toVector.flatten) -> adds)
        }
    )
  }

  private def uriFromResource(resource: Json)(using info: HydrateInfo): Option[String] = {
    if (info.isContained) {
      for {
        obj      <- resource.asObject
        id       <- obj("id")
        idString <- id.asString
      } yield s"#$idString"
    } else referenceProvider.referenceUriFromResource(resource)
  }

  private def paramDefinition(using info: ChildInfo): ErrorOr[HydrationDefinition] =
    definitions(info.param.`type`)

  private def dehydratedFromJson(template: Template)(dehydrated: Json): ErrorOr[JsonObject] =
    dehydrated.asObject.orError(InvalidParamsError(template.id))

  private def pruneEmptyExtensions(fieldName: String, hydrated: Json): Option[Json] = {
    hydrated.arrayOrObject(
      Some(hydrated),
      arr => Some(Json fromValues arr.flatMap(pruneEmptyExtensions(fieldName, _))),
      obj =>
        if (fieldName == "extension" && (obj.isEmpty || ((obj.size == 1) && obj("url").isDefined))) None
        else Some(Json.fromFields(obj.toList.flatMap { case (k, v) => pruneEmptyExtensions(k, v).map(k -> _) }))
    )
  }
}

case class HydratedSingleResource(current: Json, resourceMeta: Option[HydratedResourceMeta], paramInfo: Option[ParamInfo])
case class HydratedResourceMeta(dehydrated: JsonObject, template: Template)

/** Fully hydrated - there are no template tokens remaining */
case class Hydrated(
    current: Json,
    resourceMeta: Option[HydratedResourceMeta] = None,
    otherResources: Vector[HydratedSingleResource] = Vector.empty) {
  def add(resources: Vector[HydratedSingleResource] = Vector.empty): Hydrated = {
    copy(otherResources = resources ++ otherResources)
  }

  def addMeta(dehydrated: JsonObject, template: Template): Hydrated = {
    copy(resourceMeta = Some(HydratedResourceMeta(dehydrated, template)))
  }

  def makeSingleResourceList: Vector[HydratedSingleResource] = {
    val (contained: Vector[HydratedSingleResource], uncontained: Vector[HydratedSingleResource]) =
      otherResources.partition(_.paramInfo.exists(_.isContained))

    val newCurrent =
      if (contained.nonEmpty)
        current deepMerge Json.obj("contained" -> Json.arr(contained.map(_.current): _*))
      else current

    HydratedSingleResource(newCurrent, resourceMeta, paramInfo = None) +: uncontained
  }

  def toPartHydrated: PartHydrated =
    PartHydrated(TemplateJson.fromLiteralJson(current), resourceMeta, otherResources)
}

/** Partially hydrated - there might still be template tokens remaining */
case class PartHydrated(
    current: TemplateJson,
    resourceMeta: Option[HydratedResourceMeta] = None,
    otherResources: Vector[HydratedSingleResource] = Vector.empty) {
  def toTuple = (current, otherResources)

  def add(resources: Vector[HydratedSingleResource]): PartHydrated = {
    copy(otherResources = resources ++ otherResources)
  }

  def toFullyHydrated: Either[RemainingTemplateValuesError, Hydrated] =
    current.asLiteral.map(Hydrated(_, resourceMeta, otherResources))
}

private object PartHydrated {
  def apply(current: TemplateJson, otherResources: Vector[HydratedSingleResource]): PartHydrated =
    PartHydrated(current, None, otherResources)
}
