package com.emed.hydrant

import com.emed.hydrant.TestHelpers.slurpRsc
import io.circe.parser.parse
import io.circe.generic.auto.*

object TestTypeProvider {
  private val fileNames = List(
    "Account.json",
    "ActivityDefinition.json",
    "AdverseEvent.json",
    "Age.json",
    "AllergyIntolerance.json",
    "Appointment.json",
    "AppointmentResponse.json",
    "AuditEvent.json",
    "Basic.json",
    "Binary.json",
    "BiologicallyDerivedProduct.json",
    "BodyStructure.json",
    "Bundle.json",
    "CapabilityStatement.json",
    "CarePlan.json",
    "CareTeam.json",
    "CatalogEntry.json",
    "ChargeItem.json",
    "ChargeItemDefinition.json",
    "Claim.json",
    "ClaimResponse.json",
    "ClinicalImpression.json",
    "CodeSystem.json",
    "Communication.json",
    "CommunicationRequest.json",
    "CompartmentDefinition.json",
    "Composition.json",
    "ConceptMap.json",
    "Condition.json",
    "Consent.json",
    "Contract.json",
    "Contributor.json",
    "Count.json",
    "Coverage.json",
    "CoverageEligibilityRequest.json",
    "CoverageEligibilityResponse.json",
    "DataRequirement.json",
    "DetectedIssue.json",
    "Device.json",
    "DeviceDefinition.json",
    "DeviceMetric.json",
    "DeviceRequest.json",
    "DeviceUseStatement.json",
    "DiagnosticReport.json",
    "Distance.json",
    "DocumentManifest.json",
    "DocumentReference.json",
    "DomainResource.json",
    "EffectEvidenceSynthesis.json",
    "Encounter.json",
    "Endpoint.json",
    "EnrollmentRequest.json",
    "EnrollmentResponse.json",
    "EpisodeOfCare.json",
    "EventDefinition.json",
    "Evidence.json",
    "EvidenceVariable.json",
    "ExampleScenario.json",
    "ExplanationOfBenefit.json",
    "Expression.json",
    "FamilyMemberHistory.json",
    "Flag.json",
    "Goal.json",
    "GraphDefinition.json",
    "Group.json",
    "GuidanceResponse.json",
    "HealthcareService.json",
    "ImagingStudy.json",
    "Immunization.json",
    "ImmunizationEvaluation.json",
    "ImmunizationRecommendation.json",
    "ImplementationGuide.json",
    "InsurancePlan.json",
    "Invoice.json",
    "Library.json",
    "Linkage.json",
    "List.json",
    "Location.json",
    "Measure.json",
    "MeasureReport.json",
    "Media.json",
    "Medication.json",
    "MedicationAdministration.json",
    "MedicationDispense.json",
    "MedicationKnowledge.json",
    "MedicationRequest.json",
    "MedicationStatement.json",
    "MedicinalProduct.json",
    "MedicinalProductAuthorization.json",
    "MedicinalProductContraindication.json",
    "MedicinalProductIndication.json",
    "MedicinalProductIngredient.json",
    "MedicinalProductInteraction.json",
    "MedicinalProductManufactured.json",
    "MedicinalProductPackaged.json",
    "MedicinalProductPharmaceutical.json",
    "MedicinalProductUndesirableEffect.json",
    "MessageDefinition.json",
    "MessageHeader.json",
    "MolecularSequence.json",
    "NamingSystem.json",
    "NutritionOrder.json",
    "ObservationDefinition.json",
    "OperationDefinition.json",
    "OperationOutcome.json",
    "Organization.json",
    "OrganizationAffiliation.json",
    "ParameterDefinition.json",
    "Parameters.json",
    "Patient.json",
    "PaymentNotice.json",
    "PaymentReconciliation.json",
    "Person.json",
    "PlanDefinition.json",
    "Practitioner.json",
    "PractitionerRole.json",
    "Procedure.json",
    "Provenance.json",
    "Questionnaire.json",
    "QuestionnaireResponse.json",
    "RelatedArtifact.json",
    "RelatedPerson.json",
    "RequestGroup.json",
    "ResearchDefinition.json",
    "ResearchElementDefinition.json",
    "ResearchStudy.json",
    "ResearchSubject.json",
    "Resource.json",
    "RiskAssessment.json",
    "RiskEvidenceSynthesis.json",
    "SampledData.json",
    "Schedule.json",
    "SearchParameter.json",
    "ServiceRequest.json",
    "Signature.json",
    "Slot.json",
    "Specimen.json",
    "SpecimenDefinition.json",
    "StructureDefinition.json",
    "StructureMap.json",
    "Subscription.json",
    "Substance.json",
    "SubstancePolymer.json",
    "SubstanceReferenceInformation.json",
    "SubstanceSpecification.json",
    "SupplyDelivery.json",
    "SupplyRequest.json",
    "Task.json",
    "TerminologyCapabilities.json",
    "TestReport.json",
    "TestScript.json",
    "TriggerDefinition.json",
    "UsageContext.json",
    "ValueSet.json",
    "VerificationResult.json",
    "VisionPrescription.json",
    "address.json",
    "annotation.json",
    "attachment.json",
    "backboneElement.json",
    "base64Binary.json",
    "boolean.json",
    "canonical.json",
    "code.json",
    "codeableConcept.json",
    "coding.json",
    "contactDetail.json",
    "contactPoint.json",
    "date.json",
    "dateTime.json",
    "decimal.json",
    "dosage.json",
    "duration.json",
    "element.json",
    "elementDefinition.json",
    "extension.json",
    "humanName.json",
    "id.json",
    "identifier.json",
    "instant.json",
    "integer.json",
    "markdown.json",
    "meta.json",
    "money.json",
    "moneyQuantity.json",
    "narrative.json",
    "observation.json",
    "oid.json",
    "period.json",
    "positiveInt.json",
    "quantity.json",
    "range.json",
    "ratio.json",
    "reference.json",
    "simpleQuantity.json",
    "string.json",
    "time.json",
    "timing.json",
    "unsignedInt.json",
    "uri.json",
    "url.json",
    "uuid.json",
    "xhtml.json"
  )

  lazy val typeProvider = new StructureDefinitionTypeProvider({
    for {
      fileName <- fileNames
      file = slurpRsc(s"profiles/$fileName")
      json <- parse(file).toOption
      structureDef <- json.as[StructureDefinition].toOption
    } yield structureDef
  })

}
