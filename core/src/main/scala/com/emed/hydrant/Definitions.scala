package com.emed.hydrant

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated}
import cats.kernel.Monoid
import cats.syntax.foldable.*
import HydrantError.*
import TemplateDecoding.defnDecoder
import io.circe.*
import io.circe.Decoder.decodeList
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.github.classgraph.{ClassGraph, Resource}

import java.io.File
import java.nio.file.{Files, Path}
import scala.annotation.{tailrec, targetName}
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex
import scala.util.{Try, Using}

trait Definitions {
  def apply(id: ParamType): ErrorOr[HydrationDefinition] = apply(id.entryName)
  def apply(id: String): ErrorOr[HydrationDefinition]    = lookup(id).orError(UnknownComplexTypeError(id))

  def all: Iterable[HydrationDefinition]
  def templates: Iterable[Template] = all.toSeq.collect { case t: Template => t }

  @targetName("combine")
  def ++(other: Definitions): Definitions = Definitions(this.all ++ other.all)

  def filter(predicate: HydrationDefinition => Boolean): Definitions = Definitions(all.filter(predicate))

  protected def lookup(id: String): Option[HydrationDefinition]

  /** Find value in definitions ignoring case */
  def iget(key: String): Option[HydrationDefinition] = apply(key).toOption orElse all.find(_.id.toLowerCase == key.toLowerCase)
}

object Definitions {

  val empty: Definitions = Definitions(Seq.empty)

  def apply(definitions: Iterable[HydrationDefinition]): Definitions = new Definitions {
    private lazy val map = definitions.map(d => d.id -> d).toMap

    override protected def lookup(id: String): Option[HydrationDefinition] = map.get(id)
    override def all: Iterable[HydrationDefinition]                        = definitions
  }

  val schemaRegistryPath: Regex = """(?:.*/)?dfhir/([a-zA-Z0-9_-]+)/.*""".r
  val templatePath: Regex       = """(?:.*/)?dfhir_schemas/([a-zA-Z0-9_-]+)/.*""".r
  val versionedPath: Regex      = """(?:.*/)?([a-zA-Z0-9_-]+)/([0-9]+\.[0-9]+\.[0-9]+|local)/.*\.json""".r

  case class Metadata(packageName: Option[String] = None, version: Option[String] = None)

  object Metadata {
    def fromPath(path: String): Metadata = {
      path match {
        case versionedPath(domain, version) =>
          Metadata(packageName = Some(domain), version = Some(version))
        case schemaRegistryPath(domain) =>
          println(s"Warning: no version found for $path")
          Metadata(packageName = Some(domain))
        case templatePath(domain) =>
          val version = sys.env.get("T_VERSION")
          Metadata(packageName = Some(domain), version = version)
        case _ =>
          println(s"Warning: no metadata found for $path")
          Metadata()
      }
    }
  }

  def fromClassPath(packageName: String = "template") = Try {
    val extensions    = Vector("json", "yml", "yaml")
    val scan          = new ClassGraph().acceptPackages(packageName).scan
    val templateFiles = extensions.flatMap(scan.getResourcesWithExtension(_).asScala)
    templateFiles.foldMap[Definitions](decode)
  }.recover { case e => e.printStackTrace(); throw e }.get

  def decode(templateFile: Resource): Definitions = {
    val meta = Metadata.fromPath(templateFile.getPath)
    decode(templateFile.getContentAsString, meta)
  }

  def decode(file: File): Definitions = try {
    decode(scala.io.Source.fromFile(file), Metadata.fromPath(file.getPath))
  } catch {
    case e: Exception => throw new RuntimeException(f"Failed to read definitions from $file", e)
  }

  def decode(source: scala.io.Source, meta: Metadata = Metadata()): Definitions = {
    val str = Using(source)(_.getLines().mkString).get
    decode(str, meta)
  }

  def decode(str: String): Definitions = decode(str, Metadata())

  def decode(str: String, meta: Metadata): Definitions = {
    val decoder = TemplateDecodingFor(meta)
    import TemplateDecoding.decodeEither
    val defnDecoder                    = decoder.defnDecoder
    given Decoder[HydrationDefinition] = defnDecoder.validate(_.focus.exists(_.isObject), "Not a single template")
    given Decoder[List[HydrationDefinition]] =
      decodeList(defnDecoder).validate(_.focus.exists(_.isArray), "Couldn't decode as a list: not an array")
    getDefinitions(str)
  }

  private def getDefinitions(str: String)(using d: Decoder[Either[HydrationDefinition, List[HydrationDefinition]]]): Definitions =
    Definitions(doDecode(str))

  private def doDecode(str: String)(using d: Decoder[Either[HydrationDefinition, List[HydrationDefinition]]]): List[HydrationDefinition] = {
    // copied from io.circe.parser.finishDecodeAccumulating, because io.circe.yaml.parser doesn't have that method
    val yaml = io.circe.yaml.parser.parse(str)
    val decoded = yaml match {
      case Right(json) =>
        d.decodeAccumulating(json.hcursor).leftMap { case NonEmptyList(h, t) =>
          NonEmptyList(h, t)
        }
      case Left(error) => Validated.invalidNel(error)
    }

    decoded match {
      case Valid(Left(t))   => List(t)
      case Valid(Right(ts)) => ts
      case Invalid(errors)  =>
        // circe error messages are awful, we make them clearer here
        val errorMessages = errors
          .map {
            case ParsingFailure(message, _) => f"Parsing failure: $message"
            case failure: DecodingFailure =>
              val history = if (failure.history.nonEmpty) {
                s" at ${CursorOp.opsToPath(failure.history)}"
              } else {
                ""
              }
              val message = betterErrorDictionary.foldLeft(failure.message) { case (msg, (key, value)) => msg.replaceAll(key, value) }
              f"$message$history"
          }
          .toList
          .mkString("\t", "\n\t", "")
        throw new RuntimeException(f"Couldn't decode definition, errors:\n$errorMessages\nInput:\n$str", errors.head)
    }
  }

  private val betterErrorDictionary = Map(
    "Attempt to decode value on failed cursor" -> "Missing required field"
  )

  given Monoid[Definitions] = Monoid.instance(empty, _ ++ _)

  def fromFolder(folder: File): Definitions = {

    @tailrec
    def deepFiles(remaining: Vector[File], current: Vector[File] = Vector.empty): Vector[File] = remaining match {
      case h +: t if h.isDirectory => deepFiles(h.listFiles().toVector ++ t, current)
      case h +: t if h.getName endsWith ".json" => deepFiles(t, h +: current)
      case _ +: t => deepFiles(t, current)
      case _ => current
    }

    deepFiles(Vector(folder)).foldMap(decode)
  }

  def fromFolderPath(path: String) = fromFolder(new File(path))
}
