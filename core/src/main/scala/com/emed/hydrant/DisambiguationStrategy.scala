package com.emed.hydrant

/** This determines the strategy that dehydration should use to tell apart similarly looking hydrated elements in lists */
sealed trait DisambiguationStrategy {
  def isStrictHere(path: String) = false
}

/** Error on fixed value mismatch */
case object Strict extends DisambiguationStrategy {
  override def isStrictHere(path: String) = true
}

/** Error on fixed value mismatch, except at given paths */
case class PathIgnore(paths: Set[String]) extends DisambiguationStrategy {
  override def isStrictHere(path: String) = !(paths contains path)
}

/** Disambiguate using array element order */
case object Order extends DisambiguationStrategy
