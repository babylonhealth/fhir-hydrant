package com.emed.hydrant.profilegen

import com.emed.hydrant
import com.emed.hydrant.{ Definitions, EnumDefinition, Template }
import com.babylonhealth.lit.core.UriStr
import com.babylonhealth.lit.core.model.Meta
import com.babylonhealth.lit.core.Markdown
import com.babylonhealth.lit.hl7.model.StructureDefinition

/** Tells profile generation how to generate Ids and Urls of profiles. While there are some default implementations, you can override
  * methods as required.
  */
trait IdProvider {

  /** The base url for generated profiles url field. */
  lazy val urlBase: String

  /** Ids for value sets given an optionally specified full url and an enum id */
  def valueSetId(url: Option[String], id: String): Option[String]

  lazy val profileUrlBase  = s"$urlBase/StructureDefinition/"
  lazy val valueSetUrlBase = s"$urlBase/ValueSet/"

  /** Default profile url */
  def profileUrlFromId(id: String): String = s"$profileUrlBase${transformId(id)}"

  /** Default value set url */
  def valueSetUrlFromId(id: String): String         = s"$valueSetUrlBase${transformId(id)}"
  def valueSetUrl(enumDefn: EnumDefinition): String = enumDefn.url.getOrElse(valueSetUrlFromId(enumDefn.id))

  /** Default names when none are defined */
  /** Convenience method so you only have to override one */
  def nameFromIdAndDomain(id: String, domain: String): String          = dropIfInit(id, domain)
  def extensionNameFromIdAndDomain(id: String, domain: String): String = nameFromIdAndDomain(id, domain)
  def profileNameFromIdAndDomain(id: String, domain: String): String   = nameFromIdAndDomain(id, domain)
  def valueSetNameFromIdAndDomain(id: String, domain: String): String  = nameFromIdAndDomain(id, domain)

  /** Meta entries for generated conformance resources */
  lazy val profileMeta: Option[Meta]   = None
  lazy val extensionMeta: Option[Meta] = None
  lazy val valueSetMeta: Option[Meta]  = None

  /** Extension description if none can be found in the param for the extension's value param definition */
  lazy val defaultExtensionDescription: Option[Markdown] = None

  /** Ids for value sets generated from abstract params and their fixed Coding values in child templates. */
  def getChildTemplateCodingValueSetId(template: Template, paramName: String, groupName: Option[String] = None): String =
    template.id + paramName.capitalize + groupName.map(_.capitalize).getOrElse("")

  /** A common method for modifying ids, override for example if you want generated profile ids to have a common prefix */
  def transformId(templateId: String): String = templateId

  /** Override to allow profiles to inherit properly from base profiles * */
  def baseTemplateIdsFromUrl(baseUrl: String): Seq[String] = Seq.empty

  /** Override to map certain resource types to a different default base * */
  def baseUrlByResourceType(resourceType: String, structureDefinitions: StructureDefinitions): Option[UriStr] =
    structureDefinitions.hl7DefnByType.get(resourceType).map(_.url)

  /** Get profile Id */
  def profileIdOrDefault(template: Template): String = template.profileId.getOrElse(template.id)

  /** Id suffix for profiles for grouped child templates */
  lazy val groupUrlMark = "-group"

  /** Id for profiles for grouped child templates */
  def groupProfileIdOrDefault(template: Template, group: Option[String]) =
    profileIdOrDefault(template) + group.map(groupUrlMark + _).getOrElse("")

  protected def dropIfInit(s: String, prefix: String): String = {
    val ss = s.toLowerCase
    val p  = prefix.toLowerCase
    if ((ss != p) && ss.startsWith(p)) s.drop(prefix.length) else s
  }
}

object IdProvider {
  def apply(baseUrl: String) = new IdProvider {
    override lazy val urlBase = baseUrl

    def valueSetId(url: Option[String], id: String) = url match {
      case Some(u) if u startsWith valueSetUrlBase => Some(u.drop(valueSetUrlBase.length))
      case None                                    => Some(id)
      case Some(_)                                 => None // If url from a different domain is provided then don't generate value set.
    }
  }
}
