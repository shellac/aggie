package net.rootdev.aggie

import scala.None
import collection.mutable.{ListBuffer, LinkedList, LinkedHashMap, LinkedHashSet}

/**
 * Created with IntelliJ IDEA.
 * User: pldms
 * Date: 02/05/2012
 * Time: 21:43
 * To change this template use File | Settings | File Templates.
 */

/* Taken from http://stackoverflow.com/questions/9594431/scala-groupby-preserving-insertion-order */


class GroupByOrderedImplicit[A](t: Iterable[A]) {
  def groupByOrdered[K](f: A => K): List[(K, Iterable[A])] = {
    val map = ListBuffer[(K, Iterable[A])]()
    var currentKey = f(t.head) // get value for first item
    var lastIndex = 0
    t.zipWithIndex.foreach { case(element, i) =>
      val key = f(element)
      if (key != currentKey) {
        map.append( (currentKey, t.slice(lastIndex, i)) )
        lastIndex = i
        currentKey = key
      }
    }
    map.toList
  }
}

