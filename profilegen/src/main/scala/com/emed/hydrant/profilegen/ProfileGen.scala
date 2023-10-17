package com.emed.hydrant.profilegen

import cats.implicits.*
import cats.instances.seq.*
import com.emed.hydrant.*
import com.emed.hydrant.ChildTemplate.*
import com.emed.hydrant.HydrationDefinition.*
import com.emed.hydrant.ParamType.*
import com.emed.hydrant.PathMethods.*
import com.emed.hydrant.PrimitiveParamType.*
import com.emed.hydrant.TemplateJson.Obj
import com.babylonhealth.lit.core.*
import com.babylonhealth.lit.core.ChoiceImplicits.*
import com.babylonhealth.lit.core.model.{ CodeableConcept, Coding, Meta, resourceTypeLookup }
import com.babylonhealth.lit.core.serdes.{ objectDecoder, objectEncoder }
import com.babylonhealth.lit.hl7.*
import com.babylonhealth.lit.hl7.model.ElementDefinition.Binding
import com.babylonhealth.lit.hl7.model.ElementDefinition.Slicing.Discriminator
import com.babylonhealth.lit.hl7.model.{ DomainResource, ElementDefinition, StructureDefinition }
import io.circe.Json
import io.circe.syntax.*

import java.nio.file.{ Path, Paths }
import java.time.ZonedDateTime
import java.time.ZonedDateTime.now
import java.time.format.DateTimeFormatter
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap

/** Class for generating FHIR conformance resources (profiles, value sets and extensions) from hydration definitions.
  * @param hydrate
  *   Hydration class that is used internally. Includes the full Definitions class for all of the conformance resources to be generated.
  * @param genGroupedProfiles
  *   Whether or not to generate profiles for every child template, even if they're grouped.
  * @param idProvider
  *   Class determining how to generate Ids and Urls of generated conformance resources.
  * @param referenceProvider
  *   Class determining how references should look in hydrated resources.
  * @param paths
  *   Defines the file paths to write output conformance resources to.
  * @param log
  *   Logging trait.
  * @param structureDefinitions
  *   Class for handling hl7 FHIR resource structure definitions and any additional FHIR profiles provided.
  */
class ProfileGen(override val hydrate: Hydration, genGroupedProfiles: Boolean = false)(using
    override val idProvider: IdProvider,
    referenceProvider: ReferenceProvider,
    paths: OutputPaths,
    override val log: Logs,
    override val structureDefinitions: StructureDefinitions)
    extends ValueSetGen
    with ExtensionGen {

  override val definitions: Definitions = hydrate.definitions

  private val profileCache: TrieMap[String, StructureDefinition] = TrieMap.empty

  lazy val paramNameByType: Map[String, String] = {
    for {
      template <- definitions.templates
      typeToName <- template.params
        .filter(_._2.isComplexType)
        .groupMap(_._2.`type`.entryName)(_._1.capitalize)
        .view
        .mapValues(_.distinct)
        .collect {
          case (typeName, Seq(singleParamName)) if !template.resourceType.contains(singleParamName) =>
            typeName -> singleParamName
        }
    } yield typeToName
  }.toMap

  private lazy val definitionPaths: Map[String, String] =
    definitions.all
      .filter(_.resourceType.isDefined)
      .flatMap(HydrationDefinition.allSubDefnPaths(definitions))
      .toMap

  def makeAllProfilesAndValueSets(date: ZonedDateTime = now()): Seq[ConformanceResourceFile] =
    makeProfilesAndValueSets(definitions.all.toSeq, date)

  def makeAllSubProfilesAndValueSets(defnIds: Seq[String], date: ZonedDateTime = now()): Seq[ConformanceResourceFile] =
    makeProfilesAndValueSets(
      defnIds
        .map(definitions(_).toTry.get)
        .flatMap(HydrationDefinition.allSubDefns(definitions)),
      date
    )

  def makeProfilesAndValueSets(defns: Seq[HydrationDefinition], date: ZonedDateTime = now()): Seq[ConformanceResourceFile] = {
    lazy val valueSetIds = valueSetEnums(defns)

    defns.flatMap {
      case t: Template if t.resourceType.isDefined =>
        makeProfileFromTemplate(t, date, idProvider.profileIdOrDefault(t)) ++
        makeChildTemplateCodingValueSets(t, definitions, date).map(v =>
          ConformanceResourceFile(paths.valueSetOutputPath(v.id.get), v)) ++ makeGroupProfilesAndValueSets(t, date)
      case c: ChildTemplate if c.group.isEmpty || genGroupedProfiles =>
        val baseDefinition = definitions(c.`extends`)
          .map(
            _.asTemplate
              .map(parentTemplate => idProvider.profileUrlFromId(idProvider.groupProfileIdOrDefault(parentTemplate, c.group)))
              .getOrElse(throw ExpectedTemplate(c.`extends`)))
          .toTry
          .get
        val filled = c
          .fillParentTemplate(hydrate)
          .fold(throw _, t => t.copy(baseDefinition = Some(baseDefinition)))
        makeProfileFromTemplate(filled, date, c.id)
      case e: EnumDefinition if valueSetIds.contains(e.id) =>
        idProvider.valueSetId(e.url, e.id).toList.map { id =>
          log(s"Generating value set $id")
          ConformanceResourceFile(paths.valueSetOutputPath(id), makeValueSets(id, e, date))
        }
      case _ => Nil
    }
  }

  /** Generate all sub group profiles and valuesets inherited by this template
    */
  private def makeGroupProfilesAndValueSets(template: Template, date: ZonedDateTime): Seq[ConformanceResourceFile] = {
    (for {
      group <- definitions.all.flatMap {
        case child: ChildTemplate if child.`extends` == template.id => child.group
        case _                                                      => None
      }
      profile  <- makeProfileFromTemplate(template, date, idProvider.groupProfileIdOrDefault(template, Some(group)), Some(group))
      valueset <- makeChildTemplateCodingValueSets(template, definitions, date, Some(group))
    } yield Seq(profile, ConformanceResourceFile(Paths.get(s"valueSets/${valueset.id.get}.ValueSet.json"), valueset))).flatten.toSet.toSeq
  }

  def makeProfile(template: Template, date: ZonedDateTime = now(), group: Option[String] = None): StructureDefinition = {
    val profileName = group
      .map(_.capitalize)
      .getOrElse(
        paramNameByType.getOrElse(template.id, idProvider.profileNameFromIdAndDomain(idProvider.transformId(template.id), template.domain)))

    val key = s"${template.id}:${template.domain}:$profileName:$group"
    log(s"Looking up $profileName" + group.map(" on group: " + _).getOrElse(""))
    profileCache.getOrElseUpdate(
      key, {
        log(s"Generating profile $profileName" + group.map(" on group: " + _).getOrElse(""))

        val resourceType: String =
          template.resourceType.getOrElse(throw new Exception("Cannot create profile when no resource type defined"))

        val baseEds = elementDefinitions(resourceType, template.paramByName, template.id, group = group)(template.hydrated)(using template)
        val andContained = baseEds ++ containedED(template)
        val andChoice    = andContained.flatMap(choiceElems)
        val eds          = moveBindingsToCodeableConcept(andChoice)

        if (eds.isEmpty) throw new Exception("Generated no element definitions")

        def baseDefns(url: String) = {
          idProvider.baseTemplateIdsFromUrl(url).flatMap(definitions.apply(_).toOption).flatMap(_.asTemplate.map(makeProfile(_)))
        }

        val baseProfiles = group.fold(
          template.baseDefinition.toList.flatMap(url => structureDefinitions.extraDefnsByUrl.get(toUri(url)).toList ++ baseDefns(url)))(_ =>
          baseDefns(idProvider.profileUrlFromId(idProvider.profileIdOrDefault(template))).toList)

        val dontZeroIds = eds
          .filterNot(ed => ed.id.exists(_.contains(":")) && (ed == ElementDefinition(id = ed.id, path = ed.path, max = Some("0"))))
          .flatMap(_.id)
          .toSet
        val zeroCardinality = zeroCardinalityFields(resourceType, dontZeroIds, baseProfiles)

        val id = idProvider.groupProfileIdOrDefault(template, group)

        val differential = (eds ++ zeroCardinality).filter(ed =>
          ed != ElementDefinition(id = ed.id, path = ed.path)) // filter out any useless element definitions

        val baseDefinition =
          group
            .fold(template.baseDefinition.orElse(idProvider.baseUrlByResourceType(resourceType, structureDefinitions)))(_ =>
              Some(idProvider.profileUrlFromId(idProvider.profileIdOrDefault(template))))
            .map(toCanonical)

        StructureDefinition(
          id = Some(idProvider.transformId(id)),
          meta = idProvider.profileMeta,
          url = idProvider.profileUrlFromId(id),
          baseDefinition = baseDefinition,
          // Name either from group, or from paramName (if only one) or drop domain from id
          name = profileName,
          title = Some(template.name + group.map(" for group: " + _).getOrElse("")),
          description = Some(template.description + group.map(" for group: " + _).getOrElse("")),
          kind = STRUCTURE_DEFINITION_KIND.RESOURCE,
          `type` = resourceType,
          status = PUBLICATION_STATUS.ACTIVE,
          `abstract` = false,
          derivation = Some(TYPE_DERIVATION_RULE.CONSTRAINT),
          fhirVersion = Some(FHIR_VERSION.`4.0.1`),
          date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
          differential = Some(StructureDefinition.Differential(element = LitSeq.from(differential).asNonEmpty))
        )
      }
    )

  }

  // Only generate ValueSets for enums when they are
  // 1. used as a full templated value
  // 2. in a field of one of the appropriate types
  private def valueSetEnums(defns: Seq[HydrationDefinition]): Set[String] = {
    defns.flatMap {
      case t: Template =>
        t.params.collect {
          case (paramName, paramInfo)
              if paramInfo.isComplexType
                && Template.isFullTemplatedValue(paramName, t.hydrated)
                && definitionPaths.get(paramInfo.`type`.entryName).exists(isValueSetCompatibleType) =>
            paramInfo.`type`.entryName
        }
      case _ => Nil
    }.toSet
  }

  private val bindableComplexTypes = Set("Coding", "CodeableConcept")

  private def isEnumConvertableComplexParam(types: Set[String]) = types.exists(bindableComplexTypes)

  // FIXME Currently does not support creating valuesets for the units of Quantity types
  private def isValueSetCompatibleType(path: String): Boolean =
    structureDefinitions.typesOf(path).intersect(Set("CodeableConcept", "Coding", "code", "string")).nonEmpty

  private def makeProfileFromTemplate(
      t: Template,
      date: ZonedDateTime,
      profileName: String,
      group: Option[String] = None): Seq[ConformanceResourceFile] = {

    val profile    = makeProfile(t, date, group)
    val extensions = makeExtensions(t, profile, date)

    ConformanceResourceFile(paths.profileOutputPath(idProvider.transformId(profileName)), profile) +:
    extensions.map(extension => ConformanceResourceFile(paths.extensionOutputPath(extension.id.get), extension))
  }

  private def elementDefinitions(
      path: String,
      params: Map[String, ParamInfo],
      templateId: String,
      isSlicing: Boolean = false,
      isNested: Boolean = false,
      isRepeated: Boolean = false,
      group: Option[String] = None)(hydrateTemplate: TemplateJson)(using template: Template): Vector[ElementDefinition] = {

    lazy val types = structureDefinitions.typesOf(path)

    // generate ElementDefinition for template param object
    def nestedParamEDs(paramName: String, paramInfo: ParamInfo, hydratedStr: TemplateString): Vector[ElementDefinition] = {
      val innerDefinition =
        definitions(paramInfo.`type`).getOrElse(throw new Exception(s"Unknown type ${paramInfo.`type`} for parameter $paramName"))

      innerDefinition match {
        case innerTemplate: Template =>
          val isInlinedResource = innerTemplate.resourceType.exists { rt =>
            HydrationDefinition.referenceTypes.flatten.exists(types.contains) && !types.contains(rt)
          }

          val innerElements =
            if (!isInlinedResource)
              elementDefinitions(
                innerTemplate.resourceType.getOrElse(path),
                innerTemplate.paramByName,
                innerTemplate.id,
                isNested = true,
                isRepeated = isRepeated,
                group = group)(innerTemplate.hydrated)(using innerTemplate)
                .map {
                  _.update(_.path)(p => innerTemplate.resourceType.map(rt => path + p.drop(rt.length)).getOrElse(p))
                    .update(_.id) {
                      case Some(innerId) =>
                        innerTemplate.resourceType.map(rt => path + innerId.drop(rt.length)) orElse Some(innerId)
                      case None => throw new Exception("Somehow produced an element with no id")
                    }
                }
            else Vector.empty

          if (innerElements.exists(_.path == path)) innerElements
          else
            ElementDefinition(
              path = path,
              id = Some(path),
              min = Some(paramInfo.fhirMin),
              max = maxOnlyWhenOk(path, paramInfo.fhirMax, isSlicing),
              `type` =
                if (isInlinedResource) LitSeq.empty
                else innerTemplate.resourceType.map(ComplexType.apply).map(differentialTypes(path, _)).getOrElse(LitSeq.empty)
            ) +: innerElements
        case enumDefn: EnumDefinition =>
          Vector(
            ElementDefinition(
              path = path,
              id = Some(path),
              min = Some(paramInfo.fhirMin),
              max = maxOnlyWhenOk(path, paramInfo.fhirMax, isSlicing),
              `type` = differentialTypes(path, enumDefn.fhirTypeOrDefault),
              binding =
                Option.when(hydratedStr == TemplateString.token(paramName) && isValueSetCompatibleType(definitionPaths(enumDefn.id))) {
                  Binding(
                    strength = BINDING_STRENGTH.REQUIRED,
                    valueSet = Some(idProvider.valueSetUrl(enumDefn))
                  )
                }
            )
          )
        case _ =>
          throw new Exception(s"Inner definition should be either Template or Enum: ${paramInfo.`type`}")
      }
    }

    def extensionEDs(hydratedArrs: Vector[TemplateJson]): Vector[ElementDefinition] = {
      ElementDefinition(
        id = Some(path),
        path = path,
        slicing = Some(
          ElementDefinition.Slicing(
            rules = RESOURCE_SLICING_RULES.OPEN,
            discriminator = LitSeq(ElementDefinition.Slicing.Discriminator(`type` = DISCRIMINATOR_TYPE.VALUE, path = "url"))))
      ) +:
      hydratedArrs.flatMap { innerJson =>
        addSliceName(path, extensionSliceName(innerJson, params).toTry.get)(
          elementDefinitions(path, params, templateId, true)(innerJson)
        )
      }
    }

    def slicingEDs(hydratedArrs: Vector[TemplateJson]): Vector[ElementDefinition] = {
      discriminate(path, Vector(ToDiscriminate(hydratedArrs.map(_ -> template)))) match {
        case Some(Discriminated(sliceNames, discriminator)) =>
          val (allBaseEds, allSliceEds) = (hydratedArrs zip sliceNames).foldMap { case (elem, sliceName) =>
            val baseEds      = elementDefinitions(path, params, templateId, true)(elem)
            val zeros        = zeroCardinalityFields(path, baseEds.flatMap(_.id).toSet, Nil)
            val baseAndZeros = baseEds ++ zeros
            val sliceEds     = addSliceName(path, sliceName)(baseAndZeros)

            (baseAndZeros, sliceEds)
          }

          val topSlices = allSliceEds.filter(_.path == path)
          val topElement = ElementDefinition(
            id = Some(path),
            path = path,
            slicing = Some(
              ElementDefinition.Slicing(
                rules = RESOURCE_SLICING_RULES.OPEN,
                discriminator = LitSeq(discriminator)
              )),
            min = topSlices.map(_.min).foldLeft(Option[UnsignedInt](0)) {
              case (Some(m), Some(sliceMin)) => Some(sliceMin + m)
              case _                         => None
            },
            max = topSlices.map(_.max).foldLeft(Option("0")) {
              case (Some("*"), _)            => Some("*")
              case (_, Some("*"))            => Some("*")
              case (Some(m), Some(sliceMax)) => Some((sliceMax.toInt + m.toInt).toString)
              case _                         => None
            }
          )

          val edsForAllSlices    = unionOfSlices(allBaseEds).toVector
          val (edsHere, edsDeep) = (edsForAllSlices ++ allSliceEds).partition(_.id contains path)
          val mergedTop          = edsHere.foldLeft(topElement)(mergeEds)

          mergedTop +: edsDeep
        case None =>
          log(s"Unknown slice discriminator at ${template.id} - $path")
          Vector.empty
      }
    }

    def fhirMaxMax(a: String, b: String) = if (a == "*" || b == "*") "*" else Math.max(a.toInt, b.toInt).toString

    def unionOfSlices(eds: Vector[ElementDefinition]) = {
      eds.groupBy(_.id).values.map { samePathEds =>
        samePathEds.reduce { (a, b) =>
          ElementDefinition(
            id = a.id,
            min = (a.min map2 b.min)(Math.min),
            max = (a.max map2 b.max)(fhirMaxMax),
            path = a.path,
            code = LitSeq.from(a.code.toSet intersect b.code.toSet),
            fixed = if (a.fixed == b.fixed) a.fixed else None,
            pattern = if (a.pattern == b.pattern) a.pattern else None,
            `type` = if (a.`type`.nonEmpty && b.`type`.nonEmpty) (a.`type` ++ b.`type`).distinct else LitSeq.empty
          )
        }
      }
    }

    def mergeEds(a: ElementDefinition, b: ElementDefinition) = {

      def eitherOrMerge[A](get: ElementDefinition => Option[A])(merge: (A, A) => A) = {
        val aa = get(a)
        val bb = get(b)
        (aa map2 bb)(merge).orElse(aa).orElse(bb)
      }

      def seqMerge[A](get: ElementDefinition => LitSeq[A]) = (get(a) ++ get(b)).distinct

      ElementDefinition(
        id = a.id,
        min = eitherOrMerge(_.min)(Math.min),
        max = eitherOrMerge(_.max)(fhirMaxMax),
        path = a.path,
        code = seqMerge(_.code),
        label = a.label orElse b.label,
        short = a.short orElse b.short,
        alias = seqMerge(_.alias),
        comment = a.comment orElse b.comment,
        fixed = a.fixed orElse b.fixed,
        extension = seqMerge(_.extension),
        sliceName = a.sliceName orElse b.sliceName,
        maxLength = eitherOrMerge(_.maxLength)(Math.max),
        condition = seqMerge(_.condition),
        isSummary = eitherOrMerge(_.isSummary)(_ || _),
        definition = a.definition orElse b.definition,
        pattern = a.pattern orElse b.pattern,
        isModifier = eitherOrMerge(_.isModifier)(_ || _),
        minValue = a.minValue orElse b.minValue, // TODO we dont currently support these
        maxValue = a.maxValue orElse b.maxValue, // TODO we dont currently support these
        mustSupport = eitherOrMerge(_.mustSupport)(_ || _),
        requirements = a.requirements orElse b.requirements,
        orderMeaning = a.orderMeaning orElse b.orderMeaning,
        representation = seqMerge(_.representation),
        a.base orElse b.base,
        a.defaultValue orElse b.defaultValue,
        a.contentReference orElse b.contentReference,
        a.isModifierReason orElse b.isModifierReason,
        seqMerge(_.modifierExtension),
        seqMerge(_.example),
        seqMerge(_.mapping),
        seqMerge(_.`type`),
        a.meaningWhenMissing orElse b.meaningWhenMissing,
        eitherOrMerge(_.sliceIsConstraining)(_ || _),
        a.binding orElse b.binding,
        seqMerge(_.constraint),
        a.slicing orElse b.slicing
      )
    }

    // generate ElementDefinition for non template param object
    def objectEDs(hydratedObj: Obj): Vector[ElementDefinition] = {
      val remainingTemplateParams = hydrateTemplate.remainingTemplateParams
      if (remainingTemplateParams.isEmpty && types.contains("CodeableConcept")) {
        val pattern = hydrateTemplate.asLiteralOrDie.as[CodeableConcept].getOrElse(throw new Exception("Invalid CodeableConcept pattern"))
        // TODO maybe we actually do want fixed instead of pattern here since they're never going to have other codes in this case
        Vector(ElementDefinition(id = Some(path), path = path, min = Some(1), pattern = Some(choice(pattern))))
      } else if (remainingTemplateParams.isEmpty && types.contains("Coding")) {
        val pattern = hydrateTemplate.asLiteralOrDie.as[Coding].getOrElse(throw new Exception("Invalid Coding pattern"))
        Vector(ElementDefinition(id = Some(path), path = path, min = Some(1), pattern = Some(choice(pattern))))
      } else {
        // The present of a non template param object is determined by the nested params' presents
        // i.e
        // "valueQuantity" : {
        //    "code" : "mm[Hg]",
        //    "system" : "http://unitsofmeasure.org",
        //    "unit" : "mmHg",
        //    "value" : "{{{systolicBpValue}}}"
        //  }
        // valueQuantity definition is determined by {{{systolicBpValue}}}
        val alwaysPresent = remainingTemplateParams
          .exists { name =>
            !params.getOrElse(name, throw new Exception(s"Undeclared parameter in template: $name")).isOptional
          } // TODO maybe we want forall or something
        val fhirmax = if (remainingTemplateParams.forall { name =>
            params.getOrElse(name, throw new Exception(s"Undeclared parameter in template: $name")).fhirMax == "1"
          }) "1"
        else "*"
        val fhirmin = if (alwaysPresent) 1 else 0

        // Always add this element when:
        // - Slicing, so we have something to add the sliceName too
        // - A reference, so we have something to add the targetProfiles to
        val includeElement = alwaysPresent || types.contains("CodeableConcept") || types.contains("Reference") || isSlicing

        val elementHere = if (includeElement && path.contains(".") && !isNested) {
          Vector(ElementDefinition(path = path, id = Some(path), min = Some(fhirmin), max = maxOnlyWhenOk(path, fhirmax, isSlicing)))
        } else {
          Vector.empty
        }
        val subElements = hydratedObj.fields.flatMap {
          case (key, value) if !ProfileGen.ignoreFields.contains(key) =>
            elementDefinitions(s"$path.$key", params, templateId, group = group)(value)
          case _ => Vector.empty
        }
        elementHere ++ subElements
      }
    }

    val eds = hydrateTemplate.fold(
      Vector.empty,
      b => Vector(ElementDefinition(id = Some(path), path = path, min = Some(1), fixed = Some(choice(b)))),
      n => {
        if (types.contains("integer") || types.contains("unsignedInt") || types.contains("positiveInt"))
          Vector(ElementDefinition(id = Some(path), path = path, min = Some(1), fixed = Some(choice(n.toInt.get))))
        else
          Vector(ElementDefinition(id = Some(path), path = path, min = Some(1), fixed = Some(choice(n.toBigDecimal.get))))
      },
      {
        case TemplateString.literal(str) =>
          def typedString: ElementDefinition.FixedChoice =
            if (types.contains("code")) choice(toCode(str))
            else if (types.contains("canonical")) choice(toCanonical(str))
            else if (types.contains("uri")) choice(toUri(str))
            else if (types.contains("url")) choice(toUrlStr(str))
            else if (types.contains("id")) choice(toId(str))
            else if (types.contains("oid")) choice(toOID(str))
            else if (types.contains("markdown")) choice(toMarkdown(str))
            else choice(str)

          Vector(ElementDefinition(id = Some(path), path = path, min = Some(1), fixed = Some(typedString)))

        case str: TemplateString =>
          // There's always a param because we handle the literal case above
          val paramName = str.tokens.head

          val paramInfo = params.getOrElse(paramName, throw new Exception(s"Undeclared parameter in template: $paramName"))

          def bind = Binding(
            strength = BINDING_STRENGTH.REQUIRED,
            valueSet = Some(idProvider.valueSetUrlFromId(idProvider.getChildTemplateCodingValueSetId(template, paramName, group)))
          )

          if (paramInfo.isComplexType) {

            val binding =
              Option.when(
                isParamAbstractWithImpls(paramName, paramInfo, template.id, definitions.all, group) &&
                  isEnumConvertableComplexParam(types))(bind)

            nestedParamEDs(paramName, paramInfo, str).collect {
              case ed if ed.path == path => ed.update(_.binding)(binding.orElse(_))
              case ed                    => ed
            }
          } else {
            val binding = Option.when(
              isParamAbstractWithImpls(paramName, paramInfo, template.id, definitions.all, group) &&
                isEnumConvertableParam(paramName, template)
                && types.contains("code")
            )(bind)

            Vector(
              ElementDefinition(
                path = path,
                id = Some(path),
                min = Some(paramInfo.fhirMin),
                max = maxOnlyWhenOk(path, paramInfo.fhirMax, isSlicing),
                `type` = differentialTypes(path, paramInfo.`type`),
                binding = binding
              )
            )
          }
      },
      arr => {
        if (arr.isEmpty) Vector.empty
        else if (arr.length == 1) elementDefinitions(path, params, templateId, isRepeated = true, group = group)(arr.head)
        else if (path.lastToken == "extension") extensionEDs(arr)
        else slicingEDs(arr)
      },
      objectEDs
    )

    eds match {
      // Annotate "Reference" element definition with the right "targetProfile"
      case ed +: tail if ed.path == path && types.contains("Reference") && !ed.`type`.exists(_.`code` == "Reference") =>
        val targetProfiles: Seq[Canonical] = for {
          possibleRef <- expandTemplateLiterals(template)(hydrateTemplate)
          obj         <- possibleRef.asObject.toSeq
          refKey      <- obj("reference").toSeq
          str         <- refKey.asString.toSeq
          profiles    <- referenceProvider.profilesFromReferenceUri(str)
        } yield profiles
        val edType = ElementDefinition.Type(`code` = "Reference", targetProfile = LitSeq.from(targetProfiles.toSet))
        ed.update(_.`type`)(_ :+ edType) +: tail
      case x => x
    }
  }

  private def differentialTypes(path: String, refinedType: ParamType): LitSeq[ElementDefinition.Type] = {
    val baseTypes = structureDefinitions.typesOf(path)
    val types =
      if (baseTypes.size == 1) Set.empty // no need to put types in differential if it makes no difference
      else baseTypes.intersect(Set(refinedType.entryName))

    LitSeq.from(types.toList.widen[String].sorted.map(t => ElementDefinition.Type(code = t)))
  }

  // TODO do we actually want it? Maybe we _do_ want the strict version? - Probably make this configurable
  private def moveBindingsToCodeableConcept(elements: Vector[ElementDefinition]) = {

    def updated(elems: Vector[ElementDefinition], tpe: String, pathSuffix: String) = {

      val elementsById = elems.groupBy(_.id).map {
        case (Some(id), Vector(element)) => id -> element
        case g                           => throw new Exception(s"Duplicate id element definition: $g")
      }

      val updates = {
        for {
          elem <- elems
          if elem.binding.isEmpty
          if structureDefinitions.typesOf(elem.path) contains tpe
          codeElem <- elementsById.get(s"${elem.id.get}.$pathSuffix").toList
          binding  <- codeElem.binding.toList
          update   <- List(elem.id.get -> elem.set(_.binding)(Some(binding)), codeElem.id.get -> codeElem.set(_.binding)(None))
        } yield update
      }.toMap

      elems.map(ed => updates.getOrElse(ed.id.get, ed))
    }

    // If binding on code of Coding then move back to the Coding instead
    val cUpdates = updated(elements, "Coding", "code")
    // If binding on coding of CodeableConcept then move back to the CodeableConcept instead
    updated(cUpdates, "CodeableConcept", "coding")
  }

  private def initialSegments(path: String): Set[String] =
    path
      .split('.')
      .foldLeft(Set.empty[String] -> Set.empty[String]) { case ((longestPaths, current), next) =>
        val longer =
          if (longestPaths.nonEmpty)
            longestPaths.flatMap(p => Set(s"$p.$next", s"$p.${next.withoutLastSlice}"))
          else Set(next, next.withoutLastSlice)
        (longer, longer union current)
      }
      ._2

  private def containedParamsMinMax(template: Template): (Int, Boolean) = {
    val contained    = template.params.filter(_._2.isContained)
    val min          = contained.map(_._2.fhirMin).sum
    val anyContained = contained.nonEmpty

    val innerValues = for {
      (name, info) <- template.params
      if info.isComplexType
      innerTemplate <- definitions(info.`type`.entryName).toOption.collect { case innerTemplate: Template => innerTemplate }
    } yield {
      lazy val (innerMin, anyInnerRepeated) = containedParamsMinMax(innerTemplate)
      val actualInnerMin                    = if (info.isOptional) 0 else innerMin
      (actualInnerMin, anyInnerRepeated)
    }

    innerValues.foldLeft((min, anyContained)) { case ((min, repeated), (innerMin, innerAnyContained)) =>
      (min + innerMin, repeated || innerAnyContained)
    }
  }

  private def containedED(template: Template) = template.resourceType.map { resourceType =>
    val (min, anyContained) = containedParamsMinMax(template)
    val path                = s"$resourceType.contained"
    ElementDefinition(
      id = Some(path),
      path = path,
      min = Option.when(min > 0)(min),
      max = Option.when(!anyContained)("0")
    )
  }

  private def zeroCardinalityFields(path: String, existingIds: Set[String], baseProfiles: Seq[StructureDefinition]) = {

    val resourceType  = path.toResourceType
    val subPaths      = existingIds.map(toChoiceNotation).flatMap(initialSegments)
    val dontZeroPaths = List("meta", "contained").map(p => s"$resourceType.$p")

    val zeroInBaseAlready = {
      for {
        profile <- baseProfiles
        snap = profile.snapshot.toList
        diff = profile.differential.toList
        elem <- snap.flatMap(_.element) ++ diff.flatMap(_.element)
        if elem.max.contains("0")
        id <- elem.id
      } yield id
    }.toSet

    val allZeroIds = structureDefinitions.hl7DefnByType
      .get(resourceType)
      .flatMap(_.snapshot)
      .toList
      .flatMap(_.element)
      .filter(path initialSegmentOrEq _.path)
      .filterNot(_.min.exists(_ > 0))
      .flatMap(_.id)
      .filterNot(subPaths) // FIXME This isnt filtering out value[x] when it has valueQuantity
      .filterNot(dontZeroPaths exists _.startsWith)

    // Only add zeros for the shortest paths
    val zerosNoExtending = allZeroIds.sorted
      .foldLeft(List.empty[String]) {
        case (Nil, next)                                                    => List(next)
        case (lastAdded :: tail, next) if lastAdded initialSegmentOrEq next => lastAdded :: tail
        case (shortest, next)                                               => next :: shortest
      }
      .filterNot(zeroInBaseAlready.contains)

    zerosNoExtending.flatMap(path =>
      Option.when(!noMaxFields.contains(path.lastToken.tokenPath))(ElementDefinition(id = Some(path), path = path, max = Some("0"))))
  }

  private def choiceType(choiceId: String) = {
    val sliceName = choiceId.lastToken.tokenSliceName
    val fieldName = choiceId.withoutLastSlice.dropRight(3).lastToken
    if (!sliceName.startsWith(fieldName)) throw new Exception(s"Slicename $sliceName should start with $fieldName")
    val tpe       = sliceName.drop(fieldName.length)
    val typeNames = resourceTypeLookup.keySet
    if (typeNames contains tpe) tpe else tpe.updated(0, tpe.charAt(0).toLower)
  }

  private def choiceElems(ed: ElementDefinition): Vector[ElementDefinition] =
    ed.id
      .map(toChoiceNotation)
      .toVector
      .flatMap { choiceId => // Convert valueString to value[x]:valueString
        if (!ed.id.contains(choiceId) && choiceId.isLastSliced) {
          val choice = choiceId.withoutLastSlice

          val tpe =
            if (ed.`type`.nonEmpty) ed.`type`
            else LitSeq(ElementDefinition.Type(code = choiceType(choiceId)))

          val sliceEd = ElementDefinition(id = Some(choice), path = choice.toPath, `type` = tpe)
          List(sliceEd, ed.set(_.`type`)(tpe).set(_.id)(Some(choiceId)))
        } else List(ed.update(_.id)(_.map(toChoiceNotation)))
      }

  private val noMaxFields = Set("coding", "identifier", "id")
  // Not supposed to restrict max cardinality for certain fields or when not sliced
  // Only make restriction when max is 1
  private def maxOnlyWhenOk(id: String, fhirMax: String, isSlicing: Boolean) =
    Option.when(!noMaxFields.contains(id.lastToken.tokenPath) && isSlicing && fhirMax == "1")(fhirMax)

  private def addSliceName(path: String, sliceName: String)(eds: Vector[ElementDefinition]) = {
    val pathExtension = s"$path(.*)".r
    eds
      .map(_.update(_.id) {
        case Some(pathExtension(pathSuffix)) => Some(s"$path:$sliceName$pathSuffix")
        case id                              => throw new Exception(s"ElementDefinition had invalid id: $id")
      })
  } match {
    case h +: t if h.path == path => h.set(_.sliceName)(Some(sliceName)) +: t
    case h +: _                   => throw new Exception(f"first slice element $h did not have the right path $path")
    case _                        => Vector.empty
  }

  private case class ToDiscriminate(elems: Vector[(TemplateJson, Template)], path: String = "")
  private case class Discriminated(sliceNames: Vector[String], discriminator: Discriminator)

  @tailrec
  private def discriminate(initPath: String, toDiff: Vector[ToDiscriminate]): Option[Discriminated] =
    toDiff match {
      case ToDiscriminate(es, path) +: remaining if es.exists(_._1.asString.exists(_.isSingleToken)) =>
        // Expand any complex types
        discriminate(initPath, es.traverse(resolveInnerTemplate).map(ToDiscriminate(_, path)).toVector ++ remaining)
      case ToDiscriminate(elems @ (h, _) +: t, path) +: remaining =>
        def distinctElems = (elems.toSet.size == elems.size) &&
          (elems.map(_._1.toString).flatMap(toSliceName).toSet.size == elems.size)

        // For primitives, we discriminate on fixed values
        def primitiveDiscriminated = Discriminated(
          elems.map(_._1.toString).flatMap(toSliceName),
          Discriminator(`type` = DISCRIMINATOR_TYPE.VALUE, path = path)
        )

        // If Right, then the values are fixed and different here and we can use them to discriminate
        // otherwise this contains the inner values to continue to look for a conflict
        val nextOrDiscriminated: Either[Vector[ToDiscriminate], Discriminated] = h.fold(
          Left(Vector.empty),
          _ => Either.cond(t.exists(_._1.asBoolean.isEmpty) || !distinctElems, primitiveDiscriminated, Vector.empty),
          _ => Either.cond(t.exists(_._1.asNumber.isEmpty) || !distinctElems, primitiveDiscriminated, Vector.empty),
          _ =>
            if (elems.exists(_._1.asString.forall(_.tokens.nonEmpty)) || !distinctElems)
              Left(Vector.empty) // Inline ref is not discriminator
            else Right(primitiveDiscriminated),
          _ =>
            Left({
              for {
                arrs <- elems.traverse { case (json, template) => json.asArray.map(_ -> template) }
                if arrs.forall(_._1.length == 1)
                heads <- arrs.traverse { case (arr, template) => arr.headOption.map(_ -> template) }
              } yield Vector(ToDiscriminate(heads, path))
            }.getOrElse(Vector.empty)),
          obj => {
            val fullPathHere = if (path.isEmpty) initPath else s"$initPath.$path"
            // Slice by pattern on CodeableConcept because that's how we're putting it in an ElementDefinition above
            if (structureDefinitions.typesOf(fullPathHere) == Set("CodeableConcept")) {
              elems
                .traverse(_._1.asLiteral.flatMap(_.as[CodeableConcept]))
                .toOption
                .flatMap { ccs =>
                  Option.when(ccs.forall(_.coding.length == 1)) {
                    val codings = ccs.flatMap(_.coding.headOption)

                    def distinctField(f: Coding => Option[_]) = {
                      val fields = codings.flatMap(f)
                      codings.forall(f(_).isDefined) && (fields.length == codings.length) && (fields.length == fields.toSet.size)
                    }

                    lazy val sliceNames = codings.flatMap(_.display).flatMap(toSliceName)

                    Either.cond(
                      distinctField(_.code) && distinctField(_.display) && (sliceNames.toSet.size == codings.length),
                      Discriminated(sliceNames, Discriminator(`type` = DISCRIMINATOR_TYPE.PATTERN, path = path)),
                      Vector.empty
                    )
                  }
                }
                .getOrElse(Left(Vector.empty))
            } else
              Left(
                elems
                  .traverse { case (json, template) => json.asObject.map(_ -> template) }
                  .map { bObjs =>
                    obj.keys.toVector.flatMap { key =>
                      bObjs
                        .traverse { case (obj, template) => obj(key).map(_ -> template) }
                        .map(ToDiscriminate(_, s"$path${if (path.nonEmpty) "." else ""}$key"))
                    }
                  }
                  .getOrElse(Vector.empty)
              )
          }
        )

        nextOrDiscriminated match {
          case Left(inner)          => discriminate(initPath, inner ++ remaining)
          case Right(discriminated) => Some(discriminated)
        }
      case _ => None
    }

  private def resolveInnerTemplate(jsonAndTemplate: (TemplateJson, Template)) = {
    val (json, template) = jsonAndTemplate
    json match {
      case obj: TemplateJson.Obj => Some(obj -> template)
      case TemplateString.token(paramName) =>
        template.paramByName.get(paramName).map(_.`type`).flatMap(definitions(_).toOption).collect { case innerTemplate: Template =>
          innerTemplate.hydrated -> innerTemplate
        }
      case _ => None
    }
  }

  private def toSliceName(name: String) = {
    val sliceName = name.split(' ').map(_.capitalize).mkString.filter(c => c.isLetterOrDigit || c == '-' || c == '/').capitalize
    Option.when(sliceName.nonEmpty)(sliceName)
  }

  /** Expand a definition into all possible values, leaving placeholders such as 0 or "ANY_STRING" for free parameters.
    *
    * For example, EncounterReference {"reference": "{{{encounterType}}}/{{{encounterId}}}"} might expand to: [ {"reference":
    * "https://consultation.bbl.health/Encounter/ANY_STRING"}, {"reference": "https://triage.bbl.health/Encounter/ANY_STRING"},
    * {"reference": "https://chat.bbl.health/Encounter/ANY_STRING"} ]
    */
  private def expandDefinitionLiterals(definition: HydrationDefinition): Seq[Json] = definition match {
    case enumDef: EnumDefinition => enumDef.values.map(_.value) ++ enumDef.default
    case _: ChildTemplate        => Seq.empty
    case template: Template      => expandTemplateLiterals(template)(template.hydrated)
  }

  private def expandTemplateLiterals(template: Template)(json: TemplateJson): Seq[Json] = {
    def expandParam(paramName: String): Seq[Json] = template.paramByName
      .get(paramName)
      .toSeq
      .map(_.`type`)
      .flatMap {
        // Generate arbitrary default values for free params with too many possible options
        case `int64` | `decimal` | `integer` | `positiveInt` | `unsignedInt` =>
          Seq(Json.fromInt(0))
        case `id` | `code` | `markdown` | `string` | `xhtml` =>
          Seq(Json.fromString("ANY_STRING"))
        case `boolean`                   => Seq(Json.fromBoolean(false))
        case `date` | `dateTime`         => Seq(Json.fromString("1970-01-01"))
        case `instant`                   => Seq(Json.fromString("1970-01-01T00:00:00+Z"))
        case `time`                      => Seq(Json.fromString("00:00:00+Z"))
        case `uuid`                      => Seq(Json.fromString("f340f2a1-bcba-47ae-88a8-0a823e2bde55"))
        case `uri` | `url` | `canonical` => Seq(Json.fromString("https://www.example.com"))
        case `base64Binary`              => Seq(Json.fromString("0000"))
        case `oid`                       => Seq(Json.fromString("urn:oid:1.2.3.4.5"))
        case typ: ComplexType            => definitions(typ).map(expandDefinitionLiterals).toTry.get
      }

    json.fold(
      jsonNull = Seq(Json.Null),
      jsonBoolean = b => Seq(Json.fromBoolean(b)),
      jsonNumber = n => Seq(Json.fromJsonNumber(n)),
      jsonString = {
        case TemplateString.token(param) => expandParam(param)
        case str                         =>
          // Find every {{{template token}}} and calculate all possible values it can take
          val substitutionsByParam = str.tokens.distinct.map { token =>
            token -> expandParam(token).flatMap(_.asString)
          }

          // Substitute each {{{template token}}} for every possible value it can take.
          substitutionsByParam
            .foldLeft(Seq(str)) { case (templateStrings, (paramName, substitutions)) =>
              for {
                templateString <- templateStrings
                substitution   <- substitutions
              } yield templateString.substitute(paramName, substitution)
            }
            .map(_.asLiteralOrDie)
      },
      jsonArray = _.traverse(expandTemplateLiterals(template)).map(Json.arr),
      jsonObject = obj => {
        // Traverse over the json object's values, expanding to all combinations
        obj.fields.toList
          .foldLeft(Seq(Map.empty[String, Json])) { case (combinations, key -> json) =>
            expandTemplateLiterals(template)(json).flatMap(expanded => combinations.map(_ + (key -> expanded)))
          }
          .map(Json.fromFields)
      }
    )
  }

  @tailrec
  private def toChoiceNotation(id: String)(using fhirTypeProvider: FhirTypeProvider): String = {
    val newId = structureDefinitions.unChoicedTypes.foldLeft(id) { case (currentId, (path, pathType, tpe)) =>
      val currentPath = currentId.toPath
      val findPath    = currentPath.indexOf(path)
      if (findPath >= 0) {
        val prePath      = currentPath.take(findPath)
        val currentMatch = currentPath.drop(findPath).tokens.take(path.count(_ == '.') + 1).mkString(".")

        if ((currentMatch == path) && ((path startsWith ".extension.value") || (fhirTypeProvider.typesOf(prePath) contains pathType))) {
          val changeTokenNum = currentPath.take(findPath + path.length).count(_ == '.')
          val tokens         = currentId.tokens
          val changeToken    = tokens(changeTokenNum)
          val slice          = changeToken.tokenSliceName
          val tokenPath      = changeToken.tokenPath
          val sliceName      = if (slice.nonEmpty) slice else tokenPath
          val newToken       = tokenPath.dropRight(tpe.length) + "[x]:" + sliceName
          tokens(changeTokenNum) = newToken

          tokens.mkString(".")
        } else currentId
      } else currentId
    }

    if (newId == id) newId else toChoiceNotation(newId)
  }
}

object ProfileGen {
  def apply(
      defns: Definitions,
      genGroupedProfiles: Boolean = false)(using IdProvider, ReferenceProvider, OutputPaths, Logs, StructureDefinitions) =
    new ProfileGen(hydrate = Hydration(defns), genGroupedProfiles)
  private val ignoreFields = Set("resourceType", "meta")

  def apply(defns: Definitions, urlBase: String): ProfileGen = {
    given IdProvider = IdProvider(urlBase)
    apply(defns)
  }
}
