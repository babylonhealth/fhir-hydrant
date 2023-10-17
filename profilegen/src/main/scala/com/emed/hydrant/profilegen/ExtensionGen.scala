package com.emed.hydrant.profilegen

import com.emed.hydrant.TemplateJson.{ Arr, Obj, Primitive }
import com.emed.hydrant.HydrationDefinition.*
import com.emed.hydrant.{ Definitions, HydrationDefinition, ParamInfo, PathMethods, Template, TemplateJson, TemplateString }
import com.babylonhealth.lit.hl7.model.StructureDefinition
import com.babylonhealth.lit.hl7.model.ElementDefinition
import com.babylonhealth.lit.core.{ FHIRDateTime, FHIRDateTimeSpecificity, UriStr }
import com.babylonhealth.lit.hl7.{ FHIR_VERSION, PUBLICATION_STATUS, STRUCTURE_DEFINITION_KIND, EXTENSION_CONTEXT_TYPE }
import com.babylonhealth.lit.core.ChoiceImplicits.*
import com.emed.hydrant.PathMethods.*
import com.babylonhealth.lit.core.*

import java.time.ZonedDateTime
import scala.collection.Set
import scala.collection.concurrent.TrieMap

trait ExtensionGen {

  val log: Logs
  val idProvider: IdProvider
  def definitions: Definitions

  private val extensionCache: TrieMap[String, StructureDefinition]       = TrieMap.empty
  private val descriptionCache: TrieMap[Template, Map[String, Markdown]] = TrieMap.empty

  private val sliceNameRegex = "^[a-zA-Z0-9\\[\\]@/_-]+$".r

  private def extensionMaxCardinality(template: Template, url: String): String = {
    val extensionsInHydrated =
      (template.hydrated.findAllByKey("extension") ++ template.hydrated.findAllByKey("modifierExtension"))
        .flatMap(_.asArray.map(_.flatMap(_.asObject))) // all extensions should be arrays of object

    def multipleInOneExtensionList = extensionsInHydrated.exists(_.count(_("url").flatMap(_.asLiteralString.toOption) contains url) > 1)
    def repeatedFieldInExtension = extensionsInHydrated.flatten.exists { extensionJsonObject =>
      extensionJsonObject("url").flatMap(_.asLiteralString.toOption).contains(url) &&
      extensionJsonObject.fields.exists {
        case (s"value$tpe", json) =>
          val paramNames = stringFieldsNoArray(json).flatMap(_.tokens)
          paramNames.exists(template.paramByName.get(_).exists(_.isRepeated))
        case _ => false
      }
    }
    if (multipleInOneExtensionList || repeatedFieldInExtension) "*" else "1"
  }

  private def stringFieldsNoArray(json: TemplateJson): Iterable[TemplateString] = json match {
    case s: TemplateString     => Vector(s)
    case obj: Obj              => obj.values.flatMap(stringFieldsNoArray)
    case _: Primitive | Arr(_) => Vector.empty
  }

  protected def makeExtensions(template: Template, profile: StructureDefinition, date: ZonedDateTime): Seq[StructureDefinition] = {

    val subTemplates = HydrationDefinition
      .allSubDefns(definitions, false)(template)
      .collect { case t: Template => t }

    val descriptions = extensionDescriptions(subTemplates)

    val valueTokenByUrl = {
      for {
        subTemplate <- subTemplates.toVector
        h = subTemplate.hydrated
        extensionArrays <- h.findAllByKey("extension") ++ h.findAllByKey("modifierExtension")
        arr             <- extensionArrays.asArray.toSeq
        extensionJson   <- arr
        extensionObj    <- extensionJson.asObject
        urlJson         <- extensionObj("url")
        url             <- urlJson.asLiteralString.toOption
        valueJson       <- extensionObj.fields.toList.collectFirst { case (s"value$tpe", value) => value }
        token           <- valueJson.asString.collect { case TemplateString.token(token) => token.capitalize }
      } yield url -> token
    }.toMap

    for {
      diff <- profile.differential.toSeq
      ed   <- diff.element
      if (ed.path endsWith "extension.url") || (ed.path endsWith "modifierExtension.url")
      fixed <- ed.fixed
      url   <- fixed.as[UriStr]
      if url startsWith idProvider.profileUrlBase
    } yield {
      val id = url.drop(idProvider.profileUrlBase.length)
      extensionCache
        .getOrElseUpdate(
          id, {
            log(s"Generating extension $id")
            val topElementDefinition = ElementDefinition(
              id = Some("Extension"),
              path = "Extension",
              max = Some(extensionMaxCardinality(template, url))
            )
            val extensionElemId = ed.id.get.dropLastToken // id should always exist so doing .get
            val extensionBaseId = extensionElemId.dropLastToken
            StructureDefinition(
              meta = idProvider.extensionMeta,
              id = Some(id),
              url = url,
              // Name either from paramName of "value" of extension, or drop bbl and domain from id
              name = valueTokenByUrl.getOrElse(url, idProvider.extensionNameFromIdAndDomain(id, template.domain)),
              title = Some(id),
              date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
              fhirVersion = Some(FHIR_VERSION.`4.0.1`),
              description = descriptions.get(url).orElse(idProvider.defaultExtensionDescription),
              status = PUBLICATION_STATUS.ACTIVE,
              baseDefinition = Some("http://hl7.org/fhir/StructureDefinition/Extension"),
              `type` = "Extension",
              `abstract` = false,
              kind = STRUCTURE_DEFINITION_KIND.COMPLEX_DATA_TYPE,
              context = LitSeq(
                StructureDefinition
                  .Context(`type` = EXTENSION_CONTEXT_TYPE.ELEMENT, expression = extensionBaseId.withoutLastSlice)
              ),
              differential = Some(StructureDefinition.Differential(element = topElementDefinition +: diff.element.collect {
                case e if e.id.exists(extensionElemId.initialSegment) =>
                  e.update(_.id)(_.map("Extension" + _.drop(extensionElemId.length)))
                    .update(_.path)("Extension" + _.drop(extensionElemId.toPath.length))
              }.asNonEmpty))
            )
          }
        )
    }
  }

  protected def extensionSliceName(hydrated: TemplateJson, params: Map[String, ParamInfo]): Either[Exception, String] = {
    val expandedTemplate = for {
      str <- hydrated.asString
      Seq(paramName) = str.tokens.toSeq
      param      <- params.get(paramName)
      definition <- definitions(param.`type`).toOption
      template   <- definition.asTemplate
    } yield template.hydrated

    val json = expandedTemplate getOrElse hydrated

    val sliceName = for {
      urlJson <- json("url")
      url     <- urlJson.asLiteralString.toOption
      sliceName = url.stripPrefix(idProvider.profileUrlBase)
      if sliceNameRegex matches sliceName // This will also catch incorrect URLs
    } yield sliceName

    sliceName.toRight(new Exception(s"Unable to obtain sliceName from extension url $json"))
  }

  private def extensionDescriptions(subTemplates: Set[Template]): Map[String, Markdown] =
    subTemplates.map(extensionDescriptions).fold(Map.empty)(_ ++ _)

  private def extensionDescriptions(subTemplate: Template): Map[String, Markdown] = descriptionCache.getOrElseUpdate(
    subTemplate, {
      def selfExtension(t: Template) =
        t.hydrated.asObject.exists(o =>
          o("url").isDefined &&
            (o("extension").exists(_.asArray.exists(_.nonEmpty)) || o.keys.exists(_.startsWith("value"))))

      def getParamDescriptionOfValue(extensionObj: Obj, t: Template): Option[Markdown] = for {
        valueJson       <- extensionObj.fields.view.filterKeys(_.startsWith("value")).values.headOption
        paramInfoOption <- valueJson.asString.collect { case TemplateString.token(paramName) => t.paramByName.get(paramName) }
        paramInfo       <- paramInfoOption
      } yield paramInfo.description

      for {
        selfIsExtension <- Vector(selfExtension(subTemplate))
        extensionJson   <- findExtensions(subTemplate.hydrated) ++ Option.when(selfIsExtension)(subTemplate.hydrated)
        extensionObj    <- extensionJson.asObject.toVector
        urlJson         <- extensionObj("url").toVector
        url             <- urlJson.asLiteralString.toOption.toVector
        description <- getParamDescriptionOfValue(extensionObj, subTemplate) orElse Option.when(selfIsExtension)(subTemplate.description)
      } yield url -> toMarkdown(description)
    }.toMap
  )

  private def findExtensions(json: TemplateJson): Vector[TemplateJson] = {
    json.arrayOrObject(
      Vector.empty,
      _.flatMap(findExtensions),
      obj =>
        obj("extension").flatMap(_.asArray).getOrElse(Vector.empty) ++
          obj("modifierExtension").flatMap(_.asArray).getOrElse(Vector.empty) ++
          obj.values.flatMap(findExtensions)
    )
  }
}
