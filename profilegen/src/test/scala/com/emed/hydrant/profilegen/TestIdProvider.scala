package com.emed.hydrant.profilegen

import com.emed.hydrant.Template
import com.babylonhealth.lit.core.model.Meta
import com.babylonhealth.lit.core.{ LitSeq, UriStr, toCanonical, toUri }

object TestIdProvider extends IdProvider {
  override lazy val urlBase: String = "https://fhir.bbl.health"

  override lazy val profileMeta = Some(Meta(profile = LitSeq("https://fhir.bbl.health/StructureDefinition/BblStructureDefinition")))
  override lazy val extensionMeta = Some(Meta(profile = LitSeq("https://fhir.bbl.health/StructureDefinition/BblStructureDefinition")))

  override def valueSetId(url: Option[String], id: String): Option[String] = url match {
    case Some(s"https://fhir.bbl.health/ValueSet/$id") => Some(id)
    case None                                          => Some(s"Bbl$id")
    case Some(_)                                       => None // If using a non-bbl value set URL then we do not generate
  }

  override def transformId(templateId: String) = s"Bbl$templateId"

  override def nameFromIdAndDomain(id: String, domain: String): String = dropIfInit(dropIfInit(id, "Bbl"), domain)

  override def baseUrlByResourceType(resourceType: String, structureDefinitions: StructureDefinitions): Option[UriStr] =
    resourceType match {
      case "Observation" => Some("https://fhir.bbl.health/StructureDefinition/BblObservation")
      case _             => structureDefinitions.hl7DefnByType.get(resourceType).map(_.url)
    }

  lazy val bblProfileGroupUrlRegex = s"$urlBase/StructureDefinition/Bbl(.*)$groupUrlMark(.*)".r
  lazy val bblProfileUrlRegex      = s"$urlBase/StructureDefinition/Bbl(.*)".r

  override def baseTemplateIdsFromUrl(baseUrl: String): Seq[String] = baseUrl match {
    case bblProfileGroupUrlRegex(templateId, _) => List(templateId)
    case bblProfileUrlRegex(templateId)         => List(templateId)
    case _                                      => Nil
  }

}
