package com.emed.hydrant.proptest

import io.circe.Json

import scala.concurrent.Future

trait ConformanceValidator {
  
  /** Returns any reasons why validation failed. Empty list means validation passed. */
  def validate(resource: Json, profileUrls: Seq[String]): Future[Seq[ValidationFailure]]
}

case class ValidationFailure(reason: String, profileUrl: Option[String] = None) {
  override def toString: String = s"${profileUrl.getOrElse("")} failed to validate because $reason"
}