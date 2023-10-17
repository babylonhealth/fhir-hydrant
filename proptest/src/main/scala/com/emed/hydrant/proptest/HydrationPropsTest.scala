package com.emed.hydrant.proptest

import com.babylonhealth.lit.core.model.{ Coding, Identifier, Resource, resourceTypeLookup, Reference as LitReference }
import com.babylonhealth.lit.core.serdes.*
import com.babylonhealth.lit.core.{ CompanionFor, FHIRObject }
import com.babylonhealth.lit.hl7.model.DomainResource
import io.circe.{ Json, JsonObject }
import org.junit.runner.RunWith
import org.scalatest.Assertion
import org.scalatest.exceptions.{ GeneratorDrivenPropertyCheckFailedException, TestFailedException }
import org.scalatestplus.junit.JUnitRunner
import com.emed.hydrant.*
import io.circe.HCursor

/** Property based testing of templates. 
 * By default runs against definitions on the classpath.
 * Extend to set your own definitions and testFilter before running.
 */
@RunWith(classOf[JUnitRunner])
class HydrationPropsTest extends TemplateTesting {

  private def check(companion: CompanionFor[_ <: FHIRObject])(resource: Json): Assertion = {
    val parsed = companion.decoder.decodeJson(resource)
    val c1: Assertion = parsed.left
      .map { failure =>
        fail(f"Hydrated output could not be parsed as FHIR:\n${failure.message}")
      }
      .left
      .getOrElse(succeed)
    val c2: Seq[Assertion] = for {
      resource  <- parsed.toSeq
      reference <- resource.nodalGetByClass(classOf[LitReference])
      ref       <- reference.reference
    } yield
      if (ref.startsWith("#")) {
        val containedId = ref.drop(1)
        resource match {
          case domainResource: DomainResource =>
            if (!domainResource.contained.exists(_.id.contains(containedId)))
              fail(s"Referenced contained resource $ref couldn't be found")
            else succeed
          case _ => fail(s"Invalid resource type for contained reference $ref")
        }
      } else succeed

    val c3: Assertion = {
      val res: Seq[Assertion] = for {
        resource <- parsed.toSeq
        coding <- resource.nodalGetByClass(classOf[Coding])
        if coding.system.isEmpty || coding.code.isEmpty
      } yield fail(s"All codings must have a system, but found: $coding")
      allPass(res)
    }

    allPass(c1 +: c2 :+ c3)
  }

  /** Absent in enum means the enum field will be empty. This function is to add the left absent field to default choice. A default
    * annotation is mandatory for child template.
    */
  def addDefaultToDehydrateIfAbsent(dehydrated: Json, template: Template, topLevel: Boolean = true): Json = {

    val flattenedParams = template.params
      .flatMap {
        case (name, info) if info.isFlattened =>
          definitions(info.`type`).toOption
        case _ => None
      }
      .flatMap(_.asTemplate)
      .flatMap(_.params)

    val allParams = (template.params ++ flattenedParams).toMap

    val recursed = dehydrated mapArray { _.map(addDefaultToDehydrateIfAbsent(_, template, false)) } mapObject { obj =>
      JsonObject.fromMap(obj.toMap.map { case (key, value) =>
        val newValue = for {
          param         <- allParams.get(key)
          definition    <- definitions(param.`type`).toOption
          innerTemplate <- definition.asTemplate
        } yield addDefaultToDehydrateIfAbsent(value, innerTemplate, false)
        key -> (newValue getOrElse value)
      })
    }

    recursed.mapObject(obj =>
      allParams.foldLeft(obj) {
        case (fields, (paramName, paramInfo))
            if !paramInfo.isAbstract && (topLevel || !paramInfo.isProvided) && !fields.contains(paramName) =>
          // If there's already an enum value that matches the default, use that instead of "ABSENT".
          (for {
            definition          <- definitions(paramInfo.`type`).toOption
            enumDefine          <- definition.asEnumDefinition
            default             <- enumDefine.default
            enumMatchingDefault <- enumDefine.values.find(_.value == default)
            enumName = enumDefine.valueName(enumMatchingDefault)
          } yield (paramName -> Json.fromString(enumName)) +: fields).getOrElse(fields)
        case (fields, (_, paramInfo)) if paramInfo.isAbstract && !fields.contains(childTemplateEnumKey) =>
          hydration.defaultChildTemplateId
            .get(template.id)
            .map(f =>
              (childTemplateEnumKey -> Json.fromString(childTemplateIdToEnumValue(f, template.id, template.enumBaseName))) +: fields)
            .getOrElse(fields)
        case (fields, _) => fields
      })
  }

  // Don't directly test templates with 'provided' params, because they MUST have a parent which provides the params.
  // These templates will still be tested when the parent is tested.
  override lazy val testFilter: HydrationDefinition => Boolean = {
    case template: Template => template.params.forall { case (_, param) => !param.isProvided }
    case _                  => true
  }

  def templateTests(template: Template, childTemplate: Option[ChildTemplate] = None): Unit = {
    val genTemplate = childTemplate.getOrElse(template)

    if (childTemplate.isEmpty) {
      "No parameter has an object or array tag" in {
        allPass(template.params.flatMap { case (paramName, paramInfo) =>
          paramInfo.tags.toSeq.flatMap(
            _.collect {
              case (tagName, tagValue) if tagValue.isObject =>
                fail(s"Tag $tagName on $paramName: value shouldn't be an object  - ${tagValue.noSpaces}")
              case (tagName, tagValue) if tagValue.isArray =>
                fail(s"Tag $tagName on $paramName: value shouldn't be an array  - ${tagValue.noSpaces}")
              case _ => succeed
            }
          )
        })
      }

      "No params in extension urls" in {
        def traverseTemplate(
            template: Template,
            hydrated: TemplateJson,
            lastPath: String = "",
            prevPath: String = ""
        ): Seq[Assertion] =
          hydrated
            .fold(
              Seq(succeed),
              _ => Seq(succeed),
              _ => Seq(succeed),
              {
                case TemplateString.literal(_) => Seq(succeed)
                case TemplateString.token(paramName) =>
                  val paramInfo = template.paramByName.getOrElse(paramName, fail(s"Could not find param with name $paramName"))
                  if (paramInfo.isComplexType) {
                    definitions(paramInfo.`type`) match {
                      case Right(t: Template) => traverseTemplate(t, t.hydrated, lastPath, prevPath)
                      case Right(_)           => Seq(succeed)
                      case Left(error)        => Seq(fail(error))
                    }
                  } else Seq(succeed)
                case TemplateString(inner) if prevPath == "extension" && lastPath == "url" =>
                  Seq(fail(s"Extensions cannot be generated for parametrised urls. Bad template string: $inner"))
                case _ => Seq(succeed)
              },
              _.flatMap(traverseTemplate(template, _, lastPath, prevPath)),
              _.fields.flatMap { case (k, v) =>
                traverseTemplate(template, v, k, lastPath)
              }
            )
            .toSeq

        allPass(traverseTemplate(template, template.hydrated))
      }
    }

    "Parse into Lit FHIR base type and check valid reference syntax" in {
      val resourceType = template.resourceType.get
      val companion    = resourceTypeLookup(resourceType)

      try {
        forAll(dehydratedGen(genTemplate) -> "dehydrated") { dehydrated =>
          withHydrated(template, dehydrated) { hydrated =>
            hydrated.asArray.fold(check(companion)(hydrated))(arr => check(companion)(arr.head))
          }
        }
      } catch {
        case e : GeneratorDrivenPropertyCheckFailedException if e.cause.exists(_.getMessage.contains("Hydrated output could not be parsed as FHIR")) => fail(e.cause.get)
      }
    }

    "Provided fields have same tags as propagated" in {

      def checkPropagationChainsTags(template: Template, chain: List[Template] = Nil): Assertion = {
        val seq: Seq[Assertion] = template.params.map {
          case (paramName, paramInfo) if paramInfo.isComplexType =>
            definitions(paramInfo.`type`).toOption.collect { case t: Template =>
              template.params.collect {
                case (name, p) if p.shouldPropagate =>
                  t.paramByName.get(name).collect {
                    case innerInfo if innerInfo.isProvided =>
                      val allSameTags = (t :: chain).forall(_.paramByName.get(name).forall(_.tags == p.tags))

                      if (!allSameTags) {
                        fail(s"Propagated param $name needs to have the same tags ${innerInfo.tags}")
                      }
                  }
              }

              checkPropagationChainsTags(t, t :: chain)
            } getOrElse succeed
          case _ => succeed
        }
        allPass(seq)
      }

      checkPropagationChainsTags(template)
    }

    if (!excludeRequireFieldCheck.contains(template.id) && template.containedResource.forall(!_)) {
      "Has required fields" in {
        forAll(dehydratedGen(genTemplate)) { dehydrated =>
          withHydrated(template, dehydrated) { hydrated =>
            allPass(hydrated.asArray.getOrElse(Vector(hydrated)).map { h =>
              val obj = h.asObject.get.toMap
              obj should contain key "resourceType"
              obj should contain key "id"
            })
          }
        }
      }
    }

    "Hydrate and then dehydrate is equivalent" in {
      forAll(dehydratedGen(genTemplate)) { dehydrated =>
        withHydrated(template, dehydrated) { hydrated =>
          val t = template.copy(params = template.params.map { case (n, p) => n -> p.copy(provided = Some(false)) })
          dehydration.dehydrate(t)(hydrated) shouldEqual Right(addDefaultToDehydrateIfAbsent(dehydrated, template).deepDropNullValues)
        }
      }
    }

  }

  override val definitionTests = {
    case template: Template if template.resourceType.isDefined => templateTests(template)
    case template: Template if template.hydrated.asArray.isDefined =>
      "All inner fields of array templates are accounted for" - {
        template.params.collect {
          case (name, info) if info.isComplexType =>
            name in {
              definitions(info.`type`) should matchPattern { case Right(_) => }
            }
        }
      }

      "Parse each element into Lit base type and check valid reference syntax" in {
        forAll(dehydratedGen(template)) { dehydrated =>
          withHydrated(template, dehydrated) { hydrated =>
            allPass(hydrated.asArray.getOrElse(Vector(hydrated)).map(check(Resource)))
          }
        }
      }

      "Hydrate and then dehydrate is equivalent" in {
        forAll(dehydratedGen(template)) { dehydrated =>
          if (template.hydrated.asArray.exists(_.exists(
              _.asString.exists {
                case TemplateString.token(paramName) => dehydrated.asObject.exists(_.contains(paramName))
                case TemplateString(inner) =>
                  fail(f"Array templates should only contain tokens, but template contained '$inner'")
              }
            )))
            withHydrated(template, dehydrated) { hydrated =>
              dehydration.dehydrate(template)(hydrated) shouldEqual Right(addDefaultToDehydrateIfAbsent(dehydrated, template).deepDropNullValues)
            }
          else succeed
        }
      }

    case child: ChildTemplate =>
      definitions(child.`extends`) match {
        case Right(parent: Template) if parent.resourceType.isDefined => templateTests(parent, Some(child))
        case _ => throw new Exception(s"Invalid parent ${child.`extends`} for child template ${child.id}. Not found or invalid type.")
      }
  }

  run()
}
