package net.rootdev.aggie

/**
 * Created with IntelliJ IDEA.
 * User: pldms
 * Date: 02/05/2012
 * Time: 21:43
 * To change this template use File | Settings | File Templates.
 */

/* Taken from http://stackoverflow.com/questions/9594431/scala-groupby-preserving-insertion-order */

import collection.mutable.{LinkedHashMap, LinkedHashSet}

class GroupByOrderedImplicit[A](t: Traversable[A]) {
  def groupByOrdered[K](f: A => K): LinkedHashMap[K, LinkedHashSet[A]] = {
    val map = LinkedHashMap[K,LinkedHashSet[A]]()
    for (i <- t) {
      val key = f(i)
      map(key) = map.lift(key).getOrElse(LinkedHashSet[A]()) + i
    }
    map
  }
}

