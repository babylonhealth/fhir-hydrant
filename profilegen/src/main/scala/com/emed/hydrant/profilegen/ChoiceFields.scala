package com.emed.hydrant.profilegen

import io.circe.parser.parse

trait ChoiceFields {

  val choiceFields: Map[String, List[String]] = Map(
    ".extension.value[x]" -> List(
      "base64Binary",
      "boolean",
      "canonical",
      "code",
      "date",
      "dateTime",
      "decimal",
      "id",
      "instant",
      "integer",
      "markdown",
      "oid",
      "positiveInt",
      "string",
      "time",
      "unsignedInt",
      "uri",
      "url",
      "uuid",
      "Address",
      "Age",
      "Annotation",
      "Attachment",
      "CodeableConcept",
      "Coding",
      "ContactPoint",
      "Count",
      "Distance",
      "Duration",
      "HumanName",
      "Identifier",
      "Money",
      "Period",
      "Quantity",
      "Range",
      "Ratio",
      "Reference",
      "SampledData",
      "Signature",
      "Timing",
      "ContactDetail",
      "Contributor",
      "DataRequirement",
      "Expression",
      "ParameterDefinition",
      "RelatedArtifact",
      "TriggerDefinition",
      "UsageContext",
      "Dosage"
    ),
    "Annotation.author[x]" -> List(
      "Reference",
      "string"
    ),
    "DataRequirement.subject[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "DataRequirement.dateFilter.value[x]" -> List(
      "dateTime",
      "Period",
      "Duration"
    ),
    "Dosage.asNeeded[x]" -> List(
      "boolean",
      "CodeableConcept"
    ),
    "Dosage.doseAndRate.dose[x]" -> List(
      "Range",
      "SimpleQuantity"
    ),
    "Dosage.doseAndRate.rate[x]" -> List(
      "Ratio",
      "Range",
      "SimpleQuantity"
    ),
    "Population.age[x]" -> List(
      "Range",
      "CodeableConcept"
    ),
    "SubstanceAmount.amount[x]" -> List(
      "Quantity",
      "Range",
      "string"
    ),
    "Timing.repeat.bounds[x]" -> List(
      "Duration",
      "Range",
      "Period"
    ),
    "TriggerDefinition.timing[x]" -> List(
      "Timing",
      "Reference",
      "date",
      "dateTime"
    ),
    "UsageContext.value[x]" -> List(
      "CodeableConcept",
      "Quantity",
      "Range",
      "Reference"
    ),
    "ActivityDefinition.subject[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "ActivityDefinition.timing[x]" -> List(
      "Timing",
      "dateTime",
      "Age",
      "Period",
      "Range",
      "Duration"
    ),
    "ActivityDefinition.product[x]" -> List(
      "Reference",
      "CodeableConcept"
    ),
    "AllergyIntolerance.onset[x]" -> List(
      "dateTime",
      "Age",
      "Period",
      "Range",
      "string"
    ),
    "AuditEvent.entity.detail.value[x]" -> List(
      "string",
      "base64Binary"
    ),
    "BiologicallyDerivedProduct.collection.collected[x]" -> List(
      "dateTime",
      "Period"
    ),
    "BiologicallyDerivedProduct.processing.time[x]" -> List(
      "dateTime",
      "Period"
    ),
    "BiologicallyDerivedProduct.manipulation.time[x]" -> List(
      "dateTime",
      "Period"
    ),
    "CarePlan.activity.detail.scheduled[x]" -> List(
      "Timing",
      "Period",
      "string"
    ),
    "CarePlan.activity.detail.product[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "ChargeItem.occurrence[x]" -> List(
      "dateTime",
      "Period",
      "Timing"
    ),
    "ChargeItem.product[x]" -> List(
      "Reference",
      "CodeableConcept"
    ),
    "Claim.supportingInfo.timing[x]" -> List(
      "date",
      "Period"
    ),
    "Claim.supportingInfo.value[x]" -> List(
      "boolean",
      "string",
      "Quantity",
      "Attachment",
      "Reference"
    ),
    "Claim.diagnosis.diagnosis[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "Claim.procedure.procedure[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "Claim.accident.location[x]" -> List(
      "Address",
      "Reference"
    ),
    "Claim.item.serviced[x]" -> List(
      "date",
      "Period"
    ),
    "Claim.item.location[x]" -> List(
      "CodeableConcept",
      "Address",
      "Reference"
    ),
    "ClaimResponse.addItem.serviced[x]" -> List(
      "date",
      "Period"
    ),
    "ClaimResponse.addItem.location[x]" -> List(
      "CodeableConcept",
      "Address",
      "Reference"
    ),
    "ClinicalImpression.effective[x]" -> List(
      "dateTime",
      "Period"
    ),
    "CodeSystem.concept.property.value[x]" -> List(
      "code",
      "Coding",
      "string",
      "integer",
      "boolean",
      "dateTime",
      "decimal"
    ),
    "Communication.payload.content[x]" -> List(
      "string",
      "Attachment",
      "Reference"
    ),
    "CommunicationRequest.payload.content[x]" -> List(
      "string",
      "Attachment",
      "Reference"
    ),
    "CommunicationRequest.occurrence[x]" -> List(
      "dateTime",
      "Period"
    ),
    "Composition.relatesTo.target[x]" -> List(
      "Identifier",
      "Reference"
    ),
    "ConceptMap.source[x]" -> List(
      "uri",
      "canonical"
    ),
    "ConceptMap.target[x]" -> List(
      "uri",
      "canonical"
    ),
    "Condition.onset[x]" -> List(
      "dateTime",
      "Age",
      "Period",
      "Range",
      "string"
    ),
    "Condition.abatement[x]" -> List(
      "dateTime",
      "Age",
      "Period",
      "Range",
      "string"
    ),
    "Consent.source[x]" -> List(
      "Attachment",
      "Reference"
    ),
    "Contract.topic[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "Contract.term.topic[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "Contract.term.offer.answer.value[x]" -> List(
      "boolean",
      "decimal",
      "integer",
      "date",
      "dateTime",
      "time",
      "string",
      "uri",
      "Attachment",
      "Coding",
      "Quantity",
      "Reference"
    ),
    "Contract.term.asset.valuedItem.entity[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "Contract.term.action.occurrence[x]" -> List(
      "dateTime",
      "Period",
      "Timing"
    ),
    "Contract.friendly.content[x]" -> List(
      "Attachment",
      "Reference"
    ),
    "Contract.legal.content[x]" -> List(
      "Attachment",
      "Reference"
    ),
    "Contract.rule.content[x]" -> List(
      "Attachment",
      "Reference"
    ),
    "Contract.legallyBinding[x]" -> List(
      "Attachment",
      "Reference"
    ),
    "Coverage.costToBeneficiary.value[x]" -> List(
      "SimpleQuantity",
      "Money"
    ),
    "CoverageEligibilityRequest.serviced[x]" -> List(
      "date",
      "Period"
    ),
    "CoverageEligibilityRequest.item.diagnosis.diagnosis[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "CoverageEligibilityResponse.serviced[x]" -> List(
      "date",
      "Period"
    ),
    "CoverageEligibilityResponse.insurance.item.benefit.allowed[x]" -> List(
      "unsignedInt",
      "string",
      "Money"
    ),
    "CoverageEligibilityResponse.insurance.item.benefit.used[x]" -> List(
      "unsignedInt",
      "string",
      "Money"
    ),
    "DetectedIssue.identified[x]" -> List(
      "dateTime",
      "Period"
    ),
    "DeviceDefinition.manufacturer[x]" -> List(
      "string",
      "Reference"
    ),
    "DeviceRequest.code[x]" -> List(
      "Reference",
      "CodeableConcept"
    ),
    "DeviceRequest.parameter.value[x]" -> List(
      "CodeableConcept",
      "Quantity",
      "Range",
      "boolean"
    ),
    "DeviceRequest.occurrence[x]" -> List(
      "dateTime",
      "Period",
      "Timing"
    ),
    "DeviceUseStatement.timing[x]" -> List(
      "Timing",
      "Period",
      "dateTime"
    ),
    "DiagnosticReport.effective[x]" -> List(
      "dateTime",
      "Period"
    ),
    "EventDefinition.subject[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "EvidenceVariable.characteristic.definition[x]" -> List(
      "Reference",
      "canonical",
      "CodeableConcept",
      "Expression",
      "DataRequirement",
      "TriggerDefinition"
    ),
    "EvidenceVariable.characteristic.participantEffective[x]" -> List(
      "dateTime",
      "Period",
      "Duration",
      "Timing"
    ),
    "ExplanationOfBenefit.supportingInfo.timing[x]" -> List(
      "date",
      "Period"
    ),
    "ExplanationOfBenefit.supportingInfo.value[x]" -> List(
      "boolean",
      "string",
      "Quantity",
      "Attachment",
      "Reference"
    ),
    "ExplanationOfBenefit.diagnosis.diagnosis[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "ExplanationOfBenefit.procedure.procedure[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "ExplanationOfBenefit.accident.location[x]" -> List(
      "Address",
      "Reference"
    ),
    "ExplanationOfBenefit.item.serviced[x]" -> List(
      "date",
      "Period"
    ),
    "ExplanationOfBenefit.item.location[x]" -> List(
      "CodeableConcept",
      "Address",
      "Reference"
    ),
    "ExplanationOfBenefit.addItem.serviced[x]" -> List(
      "date",
      "Period"
    ),
    "ExplanationOfBenefit.addItem.location[x]" -> List(
      "CodeableConcept",
      "Address",
      "Reference"
    ),
    "ExplanationOfBenefit.benefitBalance.financial.allowed[x]" -> List(
      "unsignedInt",
      "string",
      "Money"
    ),
    "ExplanationOfBenefit.benefitBalance.financial.used[x]" -> List(
      "unsignedInt",
      "Money"
    ),
    "FamilyMemberHistory.born[x]" -> List(
      "Period",
      "date",
      "string"
    ),
    "FamilyMemberHistory.age[x]" -> List(
      "Age",
      "Range",
      "string"
    ),
    "FamilyMemberHistory.deceased[x]" -> List(
      "boolean",
      "Age",
      "Range",
      "date",
      "string"
    ),
    "FamilyMemberHistory.condition.onset[x]" -> List(
      "Age",
      "Range",
      "Period",
      "string"
    ),
    "Goal.start[x]" -> List(
      "date",
      "CodeableConcept"
    ),
    "Goal.target.detail[x]" -> List(
      "Quantity",
      "Range",
      "CodeableConcept",
      "string",
      "boolean",
      "integer",
      "Ratio"
    ),
    "Goal.target.due[x]" -> List(
      "date",
      "Duration"
    ),
    "Group.characteristic.value[x]" -> List(
      "CodeableConcept",
      "boolean",
      "Quantity",
      "Range",
      "Reference"
    ),
    "GuidanceResponse.module[x]" -> List(
      "uri",
      "canonical",
      "CodeableConcept"
    ),
    "Immunization.occurrence[x]" -> List(
      "dateTime",
      "string"
    ),
    "Immunization.protocolApplied.doseNumber[x]" -> List(
      "positiveInt",
      "string"
    ),
    "Immunization.protocolApplied.seriesDoses[x]" -> List(
      "positiveInt",
      "string"
    ),
    "ImmunizationEvaluation.doseNumber[x]" -> List(
      "positiveInt",
      "string"
    ),
    "ImmunizationEvaluation.seriesDoses[x]" -> List(
      "positiveInt",
      "string"
    ),
    "ImmunizationRecommendation.recommendation.doseNumber[x]" -> List(
      "positiveInt",
      "string"
    ),
    "ImmunizationRecommendation.recommendation.seriesDoses[x]" -> List(
      "positiveInt",
      "string"
    ),
    "ImplementationGuide.definition.resource.example[x]" -> List(
      "boolean",
      "canonical"
    ),
    "ImplementationGuide.definition.page.name[x]" -> List(
      "url",
      "Reference"
    ),
    "ImplementationGuide.manifest.resource.example[x]" -> List(
      "boolean",
      "canonical"
    ),
    "Invoice.lineItem.chargeItem[x]" -> List(
      "Reference",
      "CodeableConcept"
    ),
    "Library.subject[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "Measure.subject[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "Media.created[x]" -> List(
      "dateTime",
      "Period"
    ),
    "Medication.ingredient.item[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicationAdministration.medication[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicationAdministration.effective[x]" -> List(
      "dateTime",
      "Period"
    ),
    "MedicationAdministration.dosage.rate[x]" -> List(
      "Ratio",
      "SimpleQuantity"
    ),
    "MedicationDispense.statusReason[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicationDispense.medication[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicationKnowledge.ingredient.item[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicationKnowledge.administrationGuidelines.indication[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicationKnowledge.administrationGuidelines.patientCharacteristics.characteristic[x]" -> List(
      "CodeableConcept",
      "SimpleQuantity"
    ),
    "MedicationKnowledge.drugCharacteristic.value[x]" -> List(
      "CodeableConcept",
      "string",
      "SimpleQuantity",
      "base64Binary"
    ),
    "MedicationRequest.reported[x]" -> List(
      "boolean",
      "Reference"
    ),
    "MedicationRequest.medication[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicationRequest.substitution.allowed[x]" -> List(
      "boolean",
      "CodeableConcept"
    ),
    "MedicationStatement.medication[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicationStatement.effective[x]" -> List(
      "dateTime",
      "Period"
    ),
    "MedicinalProduct.specialDesignation.indication[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicinalProductAuthorization.procedure.date[x]" -> List(
      "Period",
      "dateTime"
    ),
    "MedicinalProductContraindication.otherTherapy.medication[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicinalProductIndication.otherTherapy.medication[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "MedicinalProductInteraction.interactant.item[x]" -> List(
      "Reference",
      "CodeableConcept"
    ),
    "MessageDefinition.event[x]" -> List(
      "Coding",
      "uri"
    ),
    "MessageHeader.event[x]" -> List(
      "Coding",
      "uri"
    ),
    "NutritionOrder.enteralFormula.administration.rate[x]" -> List(
      "SimpleQuantity",
      "Ratio"
    ),
    "Observation.effective[x]" -> List(
      "dateTime",
      "Period",
      "Timing",
      "instant"
    ),
    "Observation.value[x]" -> List(
      "Quantity",
      "CodeableConcept",
      "string",
      "boolean",
      "integer",
      "Range",
      "Ratio",
      "SampledData",
      "time",
      "dateTime",
      "Period"
    ),
    "Observation.component.value[x]" -> List(
      "Quantity",
      "CodeableConcept",
      "string",
      "boolean",
      "integer",
      "Range",
      "Ratio",
      "SampledData",
      "time",
      "dateTime",
      "Period"
    ),
    "Patient.deceased[x]" -> List(
      "boolean",
      "dateTime"
    ),
    "Patient.multipleBirth[x]" -> List(
      "boolean",
      "integer"
    ),
    "PlanDefinition.subject[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "PlanDefinition.goal.target.detail[x]" -> List(
      "Quantity",
      "Range",
      "CodeableConcept"
    ),
    "PlanDefinition.action.subject[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "PlanDefinition.action.relatedAction.offset[x]" -> List(
      "Duration",
      "Range"
    ),
    "PlanDefinition.action.timing[x]" -> List(
      "dateTime",
      "Age",
      "Period",
      "Duration",
      "Range",
      "Timing"
    ),
    "PlanDefinition.action.definition[x]" -> List(
      "canonical",
      "uri"
    ),
    "Procedure.performed[x]" -> List(
      "dateTime",
      "Period",
      "string",
      "Age",
      "Range"
    ),
    "Provenance.occurred[x]" -> List(
      "Period",
      "dateTime"
    ),
    "Questionnaire.item.enableWhen.answer[x]" -> List(
      "boolean",
      "decimal",
      "integer",
      "date",
      "dateTime",
      "time",
      "string",
      "Coding",
      "Quantity",
      "Reference"
    ),
    "Questionnaire.item.answerOption.value[x]" -> List(
      "integer",
      "date",
      "time",
      "string",
      "Coding",
      "Reference"
    ),
    "Questionnaire.item.initial.value[x]" -> List(
      "boolean",
      "decimal",
      "integer",
      "date",
      "dateTime",
      "time",
      "string",
      "uri",
      "Attachment",
      "Coding",
      "Quantity",
      "Reference"
    ),
    "QuestionnaireResponse.item.answer.value[x]" -> List(
      "boolean",
      "decimal",
      "integer",
      "date",
      "dateTime",
      "time",
      "string",
      "uri",
      "Attachment",
      "Coding",
      "Quantity",
      "Reference"
    ),
    "RequestGroup.action.relatedAction.offset[x]" -> List(
      "Duration",
      "Range"
    ),
    "RequestGroup.action.timing[x]" -> List(
      "dateTime",
      "Age",
      "Period",
      "Duration",
      "Range",
      "Timing"
    ),
    "ResearchDefinition.subject[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "ResearchElementDefinition.subject[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "ResearchElementDefinition.characteristic.definition[x]" -> List(
      "CodeableConcept",
      "canonical",
      "Expression",
      "DataRequirement"
    ),
    "ResearchElementDefinition.characteristic.studyEffective[x]" -> List(
      "dateTime",
      "Period",
      "Duration",
      "Timing"
    ),
    "ResearchElementDefinition.characteristic.participantEffective[x]" -> List(
      "dateTime",
      "Period",
      "Duration",
      "Timing"
    ),
    "RiskAssessment.occurrence[x]" -> List(
      "dateTime",
      "Period"
    ),
    "RiskAssessment.prediction.probability[x]" -> List(
      "decimal",
      "Range"
    ),
    "RiskAssessment.prediction.when[x]" -> List(
      "Period",
      "Range"
    ),
    "ServiceRequest.quantity[x]" -> List(
      "Quantity",
      "Ratio",
      "Range"
    ),
    "ServiceRequest.occurrence[x]" -> List(
      "dateTime",
      "Period",
      "Timing"
    ),
    "ServiceRequest.asNeeded[x]" -> List(
      "boolean",
      "CodeableConcept"
    ),
    "Specimen.collection.collected[x]" -> List(
      "dateTime",
      "Period"
    ),
    "Specimen.collection.fastingStatus[x]" -> List(
      "CodeableConcept",
      "Duration"
    ),
    "Specimen.processing.time[x]" -> List(
      "dateTime",
      "Period"
    ),
    "Specimen.container.additive[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "SpecimenDefinition.typeTested.container.minimumVolume[x]" -> List(
      "SimpleQuantity",
      "string"
    ),
    "SpecimenDefinition.typeTested.container.additive.additive[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "StructureMap.group.rule.source.defaultValue[x]" -> List(
      "*"
    ),
    "StructureMap.group.rule.target.parameter.value[x]" -> List(
      "id",
      "string",
      "boolean",
      "integer",
      "decimal"
    ),
    "Substance.ingredient.substance[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "SubstanceReferenceInformation.target.amount[x]" -> List(
      "Quantity",
      "Range",
      "string"
    ),
    "SubstanceSpecification.moiety.amount[x]" -> List(
      "Quantity",
      "string"
    ),
    "SubstanceSpecification.property.definingSubstance[x]" -> List(
      "Reference",
      "CodeableConcept"
    ),
    "SubstanceSpecification.property.amount[x]" -> List(
      "Quantity",
      "string"
    ),
    "SubstanceSpecification.relationship.substance[x]" -> List(
      "Reference",
      "CodeableConcept"
    ),
    "SubstanceSpecification.relationship.amount[x]" -> List(
      "Quantity",
      "Range",
      "Ratio",
      "string"
    ),
    "SupplyDelivery.suppliedItem.item[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "SupplyDelivery.occurrence[x]" -> List(
      "dateTime",
      "Period",
      "Timing"
    ),
    "SupplyRequest.item[x]" -> List(
      "CodeableConcept",
      "Reference"
    ),
    "SupplyRequest.parameter.value[x]" -> List(
      "CodeableConcept",
      "Quantity",
      "Range",
      "boolean"
    ),
    "SupplyRequest.occurrence[x]" -> List(
      "dateTime",
      "Period",
      "Timing"
    ),
    "Task.input.value[x]" -> List(
      "*"
    ),
    "Task.output.value[x]" -> List(
      "*"
    ),
    "ValueSet.expansion.parameter.value[x]" -> List(
      "string",
      "boolean",
      "integer",
      "decimal",
      "uri",
      "code",
      "dateTime"
    )
  )

  val unChoicedTypes: List[(String, String, String)] =
    choiceFields
      .map { case (path, types) =>
        path.dropRight(3) -> types
      }
      .toList
      .flatMap { case (path, types) =>
        types.map(t => (path.dropWhile(_ != '.') + t.capitalize, path.takeWhile(_ != '.'), t))
      }

}
