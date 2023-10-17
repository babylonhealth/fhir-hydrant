package com.emed.hydrant

import cats.implicits.*
import TemplateJson.{ Arr, Obj, Primitive }
import HydrantError.*
import io.circe.*

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Try }

/** Used for dehydrating FHIR into dFHIR
  * @param definitions
  *   Definitions class holding all of the HydrationDefinitions
  * @param disambiguationStrategy
  *   The way in which dehydration should tell similarly looking list elements apart
  * @param strictFullListDehydrate
  *   Error when a full list cannot be dehydrated
  * @param checkNonOptional
  *   Error when a non-optional param is not present in dehydrated
  */
class Dehydration(
    definitions: Definitions,
    disambiguationStrategy: DisambiguationStrategy = Strict,
    strictFullListDehydrate: Boolean = true,
    checkNonOptional: Boolean = true)(using FhirTypeProvider) {

  def dehydrateUnsafe(template: Template, fhir: Json): Json = dehydrate(template)(fhir).toTry.get

  def dehydrate(template: Template)(fhirJson: Json): ErrorOr[Json] = template.hydrated match {
    case Arr(_) => dehydrateListTemplate(template)(fhirJson)
    case _ =>
      for {
        resourceType <- template.resourceType.orError(NoResourceTypeError(template.id))
        (fhir, rest) <- fhirJson.asArray
          .map {
            case h +: t => Right((h, t))
            case _      => Left(DehydrateEmptyArrayError(template.id))
          }
          .getOrElse(Right((fhirJson, Seq.empty)))
        dehydrated <- doDehydrate(template, resourceType, SecondaryResources(fhir, rest))(fhir).map(_ getOrElse ExtractedValue(Json.obj()))
        _          <- if (checkNonOptional) checkRequiredDfhirFields(dehydrated.dfhir, template) else Either.unit
      } yield dehydrated.dfhir
  }

  private def checkRequiredDfhirFields(dfhir: Json, template: Template) = {
    val missingDfhir = dfhir.asObject.map(missingRequiredDfhirPaths("", _, template)).getOrElse(Nil)
    HydrantError.on(missingDfhir.nonEmpty, DehydratedMissingRequiredFieldsError(template.id, missingDfhir))
  }

  private lazy val childTemplatesMap: Map[(String, Json), String] = definitions.all.collect { case v: ChildTemplate =>
    (v.`extends`, Json.fromFields(v.implement)) -> v.id
  }.toMap

  private case class PartiallyExtracted(
      dehydrated: JsonObject,
      childImplement: JsonObject,
      fromFhir: Seq[(Json, String)],
      currentFhir: Json)

  private def extractParam(template: Template, path: String, secondaryResources: SecondaryResources)(
      p: PartiallyExtracted,
      param: (String, ParamInfo)): ErrorOr[PartiallyExtracted] = {
    val (paramName, paramInfo) = param
    extractValue(paramName, paramInfo, path, secondaryResources)(template.hydrated, p.currentFhir)(using template).map { value =>

      val removedFhir = value
        .map(_.builtFromFhirs.foldLeft(p.currentFhir) { case (current, (removeJson, removePath)) =>
          subtractJsonAtPaths(current, path, removeJson, removePath)
        })
        .getOrElse(p.currentFhir)

      value
        .map { e =>
          if (paramInfo.isAbstract)
            PartiallyExtracted(
              p.dehydrated,
              p.childImplement.add(paramName, e.dfhir),
              e.builtFromFhirs ++ p.fromFhir,
              removedFhir
            )
          else
            PartiallyExtracted(
              p.dehydrated.add(paramName, e.dfhir),
              p.childImplement,
              e.builtFromFhirs ++ p.fromFhir,
              removedFhir
            )
        }
        .getOrElse(p.copy(currentFhir = removedFhir))
    }
  }

  private def doDehydrate(definition: HydrationDefinition, path: String, secondaryResources: SecondaryResources)(
      fhir: Json): ErrorOr[Option[ExtractedValue]] = {
    definition match {
      case template: Template =>
        val errorIfStrictFixedValueMismatch: Either[DehydrateFixedValueMismatch, Unit] = Option
          .when(disambiguationStrategy.isStrictHere(path))(
            fixedValueUnmatchPath(template.hydrated, fhir, path)(using template).map { case (unmatchPath, error) =>
              Left(DehydrateFixedValueMismatch(definition.id, unmatchPath, error))
            }
          )
          .flatten
          .getOrElse(Either.unit)

        def extractEachParam: ErrorOr[PartiallyExtracted] =
          template.sortedParams.foldLeftM[ErrorOr, PartiallyExtracted](
            PartiallyExtracted(JsonObject.empty, JsonObject.empty, Seq.empty[(Json, String)], fhir)
          )(extractParam(template, path, secondaryResources))

        def extractChildTemplateEnum(p: PartiallyExtracted): ErrorOr[ExtractedValue] = {
          if (p.childImplement.isEmpty) Right(ExtractedValue(Json.fromJsonObject(p.dehydrated), p.fromFhir))
          else {
            val childImplementJson = Json.fromJsonObject(p.childImplement)

            val childTemplateId = childTemplatesMap
              .get((template.id, childImplementJson))
              .orError(NoMatchingChildTemplateError(template.id, childTemplatesMap, childImplementJson))

            childTemplateId.map { id =>
              val withChildTemplateEnum =
                p.dehydrated.add(childTemplateEnumKey, Json.fromString(childTemplateIdToEnumValue(id, template.id, template.enumBaseName)))
              ExtractedValue(Json.fromJsonObject(withChildTemplateEnum), p.fromFhir)
            }
          }
        }

        for {
          _         <- errorIfStrictFixedValueMismatch
          partial   <- extractEachParam
          extracted <- extractChildTemplateEnum(partial)
        } yield Some(extracted)

      case enumDefn: EnumDefinition =>
        val nameFromFhirOrReferenced = enumDefn.nameByValue.get(fhir) match {
          case None =>
            referencedResource(path, enumDefn, fhir, secondaryResources).map(_.flatMap(enumDefn.nameByValue.get))
          case name =>
            Right(name)
        }

        nameFromFhirOrReferenced.flatMap(
          _.orError(InvalidEnumValueError(enumDefn, fhir))
            .map(_.map(Json.fromString).map(ExtractedValue(_)))
        )

      case childTemplate: ChildTemplate =>
        Left(ChildTemplateDehydrationNotSupportedError(childTemplate))
    }
  }

  private def allLeavesOptional(templateHydratedPart: TemplateJson, params: Map[String, ParamInfo]): Boolean = {
    templateHydratedPart match {
      case TemplateString.token(name) => params.get(name).exists(_.isOptional)
      case TemplateJson.Null          => true
      case _: TemplateJson.Primitive  => true
      case Arr(arr)                   => arr.forall(allLeavesOptional(_, params))
      case obj: Obj                   => obj.values.forall(allLeavesOptional(_, params))
    }
  }

  // None => fixed values all match, Some((path, error)) => fix values DONT match at path for reason error
  private def fixedValueUnmatchPath(template: TemplateJson, fhir: Json, path: String)(using t: Template): Option[(String, String)] = {
    def objects = {
      for {
        tObj <- template.asObject
        fObj <- fhir.asObject
      } yield tObj.fields.toList.collectFirstSome { case (name, templateField) =>
        if (templateField.remainingTemplateParams.size == 1) None
        else
          fObj(name)
            .map(fixedValueUnmatchPath(templateField, _, s"$path.$name"))
            .getOrElse(
              Option.when(templateField.asArray.isEmpty && !allLeavesOptional(templateField, t.paramByName))(
                s"$path.$name" -> s"Template had $templateField, but nothing found in dfhir"
              )
            ) // If template is an array, the field being missing is valid (ie array was empty)
      }
    }.getOrElse(Some(path -> s"Mismatch on template value $template"))

    def arrays = {
      for {
        tArr <- template.asArray
        fArr <- fhir.asArray
      } yield
        if (tArr.forall(_.isLiteral)) tArr == fArr
        else fArr.forall(f => tArr.exists(fixedValueUnmatchPath(_, f, path).isEmpty))
    }.getOrElse(false)

    if ((template.asLiteral contains fhir) || template.asString.exists(_.tokens.nonEmpty) || arrays)
      None
    else objects
  }

  // builtFromFhirs is the fhir the dfhir was extracted from, with the fhirpath where it came from
  private case class ExtractedValue(dfhir: Json, builtFromFhirs: Seq[(Json, String)] = Nil) {
    def toTuple = (dfhir, builtFromFhirs)
  }

  private def referencedResource(
      path: String,
      defn: HydrationDefinition,
      fhir: Json,
      secondaryResources: SecondaryResources): Either[UnexpectedReference, Option[Json]] = {
    defn.isObjectReference(path) match {
      case Some("Reference") =>
        Right(
          for {
            obj      <- fhir.asObject
            refJson  <- obj("reference")
            ref      <- refJson.asString
            resource <- secondaryResources(ref)
          } yield resource
        )

      case Some("canonical") =>
        Right(
          for {
            url      <- fhir.asString
            resource <- secondaryResources(url)
          } yield resource
        )

      case None => Right(Some(fhir))
      case _    => Left(UnexpectedReference(defn.id, path))
    }
  }

  private def extractString(
      nameKey: TemplateString,
      paramName: String,
      paramInfo: ParamInfo,
      path: String,
      secondaryResources: SecondaryResources,
      fhir: Json)(str: TemplateString)(using t: Template): ErrorOr[Option[ExtractedValue]] =
    if (str == nameKey)
      if (paramInfo.isComplexType)
        for {
          template  <- definitions(paramInfo.`type`)
          refdResc  <- referencedResource(path, template, fhir, secondaryResources)
          resource  <- refdResc.orError(UnableToFindReference(t.id, path))
          extracted <- doDehydrate(template, template.resourceType.getOrElse(path), secondaryResources)(resource)
        } yield extracted.map(e => e.copy(builtFromFhirs = e.builtFromFhirs :+ (resource, path)))
      else
        HydrantError
          .on(fhir.isArray || fhir.isObject, DehydrateObjectToPrimitiveError(paramName))
          .as(Option.when(!fhir.isNull)(ExtractedValue(fhir))) // Pull primitives, but null is None
    else if (str contains nameKey) {

      def handleEnum(value: String) = for {
        paramTypeDefn <- definitions(paramInfo.`type`)
        v <- paramTypeDefn match {
          case enumDefn: EnumDefinition =>
            val strValue = Json.fromString(value)
            enumDefn.nameByValue
              .get(strValue)
              .orError(InvalidEnumValueError(enumDefn, strValue))
              .map(_.map(Json.fromString).map(ExtractedValue(_)))
          case _ => Left(DehydrateStringUsingTemplateError(paramName, paramInfo))
        }
      } yield v

      for {
        fhirStr         <- fhir.asString.orError(DehydrateTemplateExpectsStringError(str.inner))
        nonComplexValue <- str.extractParamValue(paramName, fhirStr).orError(DehydrateTemplateStringMismatchError(str.inner))
        value <-
          if (paramInfo.isComplexType) handleEnum(nonComplexValue)
          else Right(Some(Json.fromString(nonComplexValue)).map(ExtractedValue(_)))
      } yield value

    } else Right(None)

  private def extractArray(
      nameKey: TemplateString,
      paramName: String,
      paramInfo: ParamInfo,
      path: String,
      secondaryResources: SecondaryResources,
      fhir: Json)(arr: Vector[TemplateJson])(using t: Template): ErrorOr[Option[ExtractedValue]] =
    if (disambiguationStrategy == Order)
      if (paramInfo.isRepeated)
        for {
          fhirElems <- fhir.asArray.orError(DehydrateExpectedArrayError(t.id, path))
          templateElem = arr.lastOption.filter(_ contains nameKey) // repeated elements should come last for Order disambiguation
          arrValues <- templateElem.toSeq
            .traverse(e => fhirElems.traverse(extractValue(paramName, paramInfo, path, secondaryResources)(e, _)).map(_.flatten))
          _ <- HydrantError.on(
            strictFullListDehydrate && (arr == Vector(nameKey)) && arrValues.exists(_.isEmpty),
            UnableToDehydrateAllValuesError(t.id, path))
          (arrJsons, arrExtractedFromFhirs) = arrValues.flatten.map(_.toTuple).unzip
          dfhirArrayOption <-
            if (arrValues.iterator.nonEmpty) arr.dropRight(1).traverse(_.asLiteral).map(ls => Some(ls ++ arrJsons)) else Right(None)
        } yield dfhirArrayOption.map(dfhirs => ExtractedValue(Json.fromValues(dfhirs), arrExtractedFromFhirs.flatten))
      else
        for {
          fhirArr   <- fhir.asArray.orError(DehydrateExpectedArrayError(t.id, path))
          arrValues <- (arr zip fhirArr) traverse extractValue(paramName, paramInfo, path, secondaryResources)
          (arrJsons, arrExtractedFromFhirs) = arrValues.flatten.map(_.toTuple).unzip
          templateFieldIndex                = arr.indexWhere(_ contains nameKey)
        } yield Option
          .when(templateFieldIndex >= 0)(templateFieldIndex)
          .flatMap(arrValues.get(_).flatten)
          .orElse(arrValues.find(_.isDefined).flatten)
          .map {
            case firstValue if !paramInfo.isRepeated => firstValue
            case _                                   => ExtractedValue(Json.fromValues(arrJsons), arrExtractedFromFhirs.flatten)
          }
    else
      fhir.asArray
        .flatTraverse { fhirArr =>
          val token = TemplateString.token(paramName)
          val otherParamFixedFields =
            arr
              .filterNot(_ contains token)
              .flatMap(fixedFields)
              .filter(_.asObject.forall(_.fields.nonEmpty))

          val filtered  = fhirArr.filterNot(fhirElem => otherParamFixedFields.exists(deepJsonSubset(_, fhirElem)))
          val extracted = patternMatchExtract(paramName, paramInfo, path, secondaryResources)(arr.filter(_ contains nameKey), filtered)
          if (paramInfo.isRepeated)
            HydrantError
              .on(
                strictFullListDehydrate && (arr == Vector(nameKey)) && (extracted.length != fhirArr.length),
                UnableToDehydrateAllValuesError(t.id, path)
              )
              .as {
                val (dfhirs, fromFhirs) = extracted.map(_.toTuple).unzip
                Option.when(extracted.nonEmpty)(ExtractedValue(Json.fromValues(dfhirs), fromFhirs.flatten))
              }
          else
            Right { // FIXME inefficient to call patternMatchExtract after we find the first result which is all we use
              val (dfhir, fromFhirs) = extracted.headOption.map(_.toTuple).unzip
              dfhir.map(ExtractedValue(_, fromFhirs.getOrElse(Seq.empty)))
            }
        }

  private def extractValue(paramName: String, paramInfo: ParamInfo, path: String, secondaryResources: SecondaryResources)(
      template: TemplateJson,
      fhir: Json)(using t: Template): ErrorOr[Option[ExtractedValue]] = {
    val nameKey = TemplateString.token(paramName)
    if (paramInfo.isProvided) Right(None)
    else
      template.fold(
        Right(None),
        _ => Right(None),
        _ => Right(None),
        extractString(nameKey, paramName, paramInfo, path, secondaryResources, fhir),
        extractArray(nameKey, paramName, paramInfo, path, secondaryResources, fhir),
        obj =>
          for {
            fhirObj <- fhir.asObject.orError(DehydrateExpectedObjectError(t.id, path))
            fhirObjMap = fhirObj.toMap
            commonKeys = obj.keys.intersect(fhirObjMap.keySet).toSeq
            objFields <- commonKeys
              .traverse(key => extractValue(paramName, paramInfo, s"$path.$key", secondaryResources)(obj(key).get, fhirObjMap(key)))
              .map(_.flatten)
          } yield Option.when(objFields.nonEmpty)(objFields.head)
      )
  }

  private def patternMatchExtract(paramName: String, paramInfo: ParamInfo, path: String, secondaryResources: SecondaryResources)(
      templateArr: Vector[TemplateJson],
      fhirArr: Vector[Json])(using Template) =
    templateArr
      .foldLeft((fhirArr, Vector.empty[ExtractedValue])) { case ((remainingFhirs, groupedWithMatchingTemplate), arrayElem) =>
        val (extractedMatching, nonMatching) = remainingFhirs.partitionMap { f =>
          val allObjectFieldsArePossible = for {
            templateObj <- arrayElem.asObject
            fhirObj     <- f.asObject
          } yield fhirObj.keys.forall(templateObj.keys)

          def fixedValsMatch = fixedValueUnmatchPath(arrayElem, f, path).isEmpty

          if (allObjectFieldsArePossible.getOrElse(true) && fixedValsMatch) {
            extractValue(paramName, paramInfo, path, secondaryResources)(arrayElem, f).toOption.flatten
              .filter { extracted =>
                if (paramInfo.isComplexType)
                  definitions(paramInfo.`type`).toOption.forall {
                    case t: Template => extracted.dfhir.asObject.map(missingRequiredDfhirPaths("", _, t)).forall(_.isEmpty)
                    case _           => true
                  }
                else true
              }
              .toLeft(f)
          } else Right(f)
        }

        (nonMatching, groupedWithMatchingTemplate ++ extractedMatching)
      }
      ._2

  private def missingRequiredDfhirPaths(path: String, dehydrated: JsonObject, template: Template): List[String] =
    template.params.flatMap { case (paramName, paramInfo) =>
      def hasDefault = definitions(paramInfo.`type`) match {
        case Right(enumDef: EnumDefinition) => enumDef.default.isDefined
        case _                              => false
      }

      def required = !paramInfo.isOptional && !paramInfo.isAbstract && !hasDefault
      def absent =
        paramInfo.isComplexType && dehydrated(paramName).flatMap(_.asString).exists(_ endsWith "_ABSENT") // FIXME Could be more rigorous

      def innerJsons =
        dehydrated(paramName)
          .map(_.arrayOrObject(Vector.empty, _.flatMap(_.asObject), Vector(_)))
          .getOrElse(Vector.empty)

      if (paramInfo.isProvided) Nil
      else if (required && (dehydrated(paramName).isEmpty || absent)) List(s"$path.$paramName")
      else if (paramInfo.isComplexType)
        for {
          innerTemplate   <- definitions(paramInfo.`type`).toList.collect { case t: Template => t }
          innerDehydrated <- innerJsons
          missingPath     <- missingRequiredDfhirPaths(s"$path.$paramName", innerDehydrated, innerTemplate)
        } yield missingPath
      else Nil
    }

  private case class InnerAndProvided(paramName: String, dehydrated: Json, providedFields: List[(String, Json)], fhirs: Seq[Json])

  private def dehydrateListTemplate(template: Template)(json: Json): ErrorOr[Json] = {

    val fhirs = json.asArray.getOrElse(Vector(json))

    val innerFieldsAndProvided: Seq[InnerAndProvided] = fhirs.flatMap { fhir =>
      template.params.map {
        case (paramName, paramInfo) if paramInfo.isComplexType =>
          definitions(paramInfo.`type`).toOption.collectFirstSome {
            case defn: Template if defn.resourceType.isDefined =>
              {
                for {
                  resourceType <- defn.resourceType.orError(NoResourceTypeError(template.id))
                  allNonProvidedDefn = defn.copy(params =
                    defn.params.map(_.map(_.copy(provided = None)))) // provided params dont appear in dehydrated, but we need them here
                  dehydrated <- doDehydrate(allNonProvidedDefn, resourceType, SecondaryResources(fhir, fhirs.filterNot(_ == fhir)))(fhir)
                    .map(_ getOrElse ExtractedValue(Json.obj()))
                  providedFields: Set[String] = defn.params.collect { case (name, info) if info.isProvided => name }.toSet
                  abstractFields: Set[String] = defn.params.collect { case (name, info) if info.isAbstract => name }.toSet
                } yield dehydrated.dfhir.asObject.map(missingRequiredDfhirPaths("", _, defn)) match {
                  case None | Some(Nil) =>
                    val provided = dehydrated.dfhir.asObject
                      .map(_.toList.filter(providedFields contains _._1).filterNot(abstractFields contains _._1))
                      .getOrElse(Nil) // collect provided fields so we can populate them at the parent level
                    Some(
                      InnerAndProvided(
                        paramName,
                        dehydrated.dfhir.mapObject(_.filterKeys(!providedFields(_))),
                        provided,
                        fhir +: dehydrated.builtFromFhirs.map(_._1)
                      )
                    ) // remove provided fields from inner dfhir
                  case Some(error) => None
                }
              }.toOption.flatten
            case _ => None
          }
        case _ => None
      }
    }.flatten

    val usedFhirs = innerFieldsAndProvided.flatMap(_.fhirs).toSet

    def innerFields = innerFieldsAndProvided
      .groupBy(_.paramName)
      .toSeq
      .flatTraverse { case (paramName, sameParamExtracted) =>
        for {
          paramInfo <- template.paramByName.get(paramName).orError(UnrecognisedParam(paramName, template.id))
          value <-
            if (paramInfo.isRepeated) Right(Json.fromValues(sameParamExtracted.map(_.dehydrated)))
            else if (sameParamExtracted.size == 1) Right(sameParamExtracted.head.dehydrated)
            else Left(MoreThanOneValueForNonRepeatedFieldError(paramName, template.id))
        } yield
          if (paramInfo.isFlattened)
            value.asObject.map(_.toList).getOrElse(List(paramName -> value))
          else
            List(paramName -> value)
      }

    for {
      _ <- HydrantError.on(!fhirs.forall(usedFhirs.contains), UnableToDehydrateAllFhirResourcesError(template.id, fhirs.toSet, usedFhirs))
      providedFields = innerFieldsAndProvided.flatMap(_.providedFields)
      inner <- innerFields
    } yield Json.fromFields(providedFields ++ inner)
  }

  private def fixedFields(json: TemplateJson): Option[TemplateJson] = json match {
    case TemplateString.token(_) => None
    case p: Primitive            => p.some
    case Arr(arr)                => TemplateJson.Arr(arr.flatMap(fixedFields)).some
    case Obj(obj)                => Obj.from(obj.flatMap { case k -> v => fixedFields(v).map(k -> _) }).some
  }

  private def deepJsonSubset(a: TemplateJson, b: Json): Boolean = (a.asLiteral contains b) ||
    a.arrayOrObject(
      false,
      _.forall(aa => b.asArray.exists(_.exists(deepJsonSubset(aa, _)))),
      _.fields.forall { case (key, value) =>
        b.asObject.exists {
          _(key).exists(deepJsonSubset(value, _))
        }
      }
    )

  // Given 2 jsons and fhirpaths where they are, remove b from within a
  private def subtractJsonAtPaths(aJson: Json, aPath: String, bJson: Json, bPath: String): Json =
    if (bPath startsWith s"$aPath.") {
      val nextPath = bPath.drop(aPath.length + 1)
      // All cursors of a at that go down to b
      val innerCursors = nextPath.split('.').foldLeft(Vector[ACursor](aJson.hcursor)) { (cursors, key) =>
        cursors.flatMap(_.downField(key).success).map(c => c.downArray.success.getOrElse(c))
      }
      innerCursors
        .find(_.focus.contains(bJson))
        .map(_.delete)
        .flatMap(_.top)
        .getOrElse(aJson)
    } else aJson

}
