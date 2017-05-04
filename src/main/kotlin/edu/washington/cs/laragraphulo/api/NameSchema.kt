package edu.washington.cs.laragraphulo.api

import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import edu.washington.cs.laragraphulo.opt.Name
import org.apache.accumulo.core.client.lexicoder.IntegerLexicoder
import org.apache.accumulo.core.client.lexicoder.Lexicoder
import org.apache.accumulo.core.client.lexicoder.impl.AbstractLexicoder
import java.util.*
import kotlin.NoSuchElementException
import kotlin.collections.ArrayList


// ======================= HELPER FUNCTIONS

fun <E> Collection<E>.disjoint(other: Collection<E>): Boolean {
  return this.none { other.contains(it) }
}

/**
 * Return a NameTuple with the same keys but the values set to the default values.
 */
fun NameTuple.copyDefault(ns: NameSchema): NameTuple {
  require(this.keys == (ns.keys + ns.vals).toSet())
  return this.mapValues { (attr, value) ->
    ns.getValue(attr)?.default ?: value
  }
}


// ======================= ATTRIBUTES
const val ZERO_BYTE: Byte = 0
val SINGLE_ZERO = byteArrayOf(ZERO_BYTE) // sort null values first

/** Would this come in handy? Uses an extra byte to flag null values. Probably not. */
class NullLexicoder<T>(
    private val lexicoder: Lexicoder<T>
) : AbstractLexicoder<T>() {
  override fun encode(v: T): ByteArray {
    return if (v == null) {
      SINGLE_ZERO
    } else {
      val e = lexicoder.encode(v)
      val r = ByteArray(e.size+1)
      r[0] = 1
      System.arraycopy(e,0,r,1,e.size)
      r
    }
  }

  override fun decodeUnchecked(b: ByteArray, offset: Int, len: Int): T? {
    return if (b.size == 1 && b[0] == ZERO_BYTE) null
    else decodeUnchecked(b, 1, b.size-1)
  }
}


open class Attribute<T>(
    val name: Name,
    val type: LType<T>
) : Comparable<Attribute<T>> {

  open fun withNewName(n: Name) = Attribute(n, type)

  override fun toString(): String {
    return "Attribute(name='$name', type=$type)"
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other?.javaClass != javaClass) return false

    other as Attribute<*>

    if (name != other.name) return false
    if (type != other.type) return false

    return true
  }

  override fun hashCode(): Int {
    var result = name.hashCode()
    result = 31 * result + type.hashCode()
    return result
  }

  /** Careful: this returns 0 on objects that are not equal */
  override fun compareTo(other: Attribute<T>): Int = name.compareTo(other.name)
}

class ValAttribute<T>(
    name: Name,
    type: LType<T>,
    val default: T
) : Attribute<T>(name, type) {

  override fun withNewName(n: Name) = ValAttribute(n, type, default)

  override fun toString(): String {
    return "ValAttribute(name='$name', type=$type, default=$default)"
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other?.javaClass != javaClass) return false
    if (!super.equals(other)) return false

    other as ValAttribute<*>

    if (default != other.default) return false

    return true
  }

  override fun hashCode(): Int {
    var result = super.hashCode()
    result = 31 * result + (default?.hashCode() ?: 0)
    return result
  }
}



// ======================= SCHEMA

data class NameSchema(
  val keys: List<Attribute<*>>,
  val vals: List<ValAttribute<*>>
) {
  init {
    val kns = keys.map(Attribute<*>::name)
    val vns = vals.map(ValAttribute<*>::name)
    require(kns.let { it.size == it.toSet().size }) {"there is a duplicate key attribute name: $keys"}
    require(vns.let { it.size == it.toSet().size }) {"there is a duplicate value attribute name: $vals"}
    require(kns.disjoint(vns)) { "keys and vals overlap: $keys, $vals" }
  }

  operator fun get(n: Name): Attribute<*>? =
      keys.find { it.name == n } ?: vals.find { it.name == n }

  fun getValue(n: Name): ValAttribute<*>? =
      vals.find { it.name == n }
}

// ======================= TUPLE

typealias NameTuple = Map<Name,*>



// ======================= UDFs

open class ExtFun(
    /** The (to be appended) new key attributes and value attributes that the extFun produces */
    val extSchema: NameSchema,
    val extFun: (NameTuple) -> List<NameTuple>
) {
  override fun toString(): String {
    return "ExtFun(extSchema=$extSchema, extFun=$extFun)"
  }
}

/**
 * Must return default values when passed default values, for any key.
 */
class MapFun(
    /** The value attributes that the mapFun produces */
    val mapValues: List<ValAttribute<*>>,
    val mapFun: (NameTuple) -> NameTuple
) : ExtFun(extSchema = NameSchema(listOf(), mapValues),
               extFun = { tuple -> listOf(mapFun(tuple)) }) {
  override fun toString(): String {
    return "MapFun(mapValues=$mapValues, mapFun=$mapFun)"
  }
}



data class PlusFun<T>(
    val identity: T,
    val plus: (T, T) -> T
) {
  fun verifyIdentity(a: T = identity) {
    check(plus(a,identity) == a && plus(identity,a) == a) {"Value $a violates the identity requirement of plus for identity $identity"}
  }

  companion object {
    /** Wraps a function to have an identity. */
    inline fun <T> withIdentity(id: T, crossinline plusFun: (T,T) -> T) = PlusFun(id) { a, b ->
      when {
        a == id -> b
        b == id -> a
        else -> plusFun(a,b)
      }
    }

    /** Wraps a function to have identity null (that is zero-sum-free). */
    inline fun <T : Any> withNullIdentity(crossinline plusFun: (T, T) -> T): PlusFun<T?> {
      return PlusFun<T?>(null) { a, b ->
        when {
          a == null -> b
          b == null -> a
          else -> plusFun(a,b)
        }
      }
    }

    /** Use this when you know that summation will never occur. Throws an error when summing two non-identities. */
    fun <T> plusErrorFun(id: T) = PlusFun(id) { a, b ->
      when {
        a == id -> b
        b == id -> a
        else -> throw IllegalStateException("no plus function defined for this attribute, yet non-identity ($id) values $a and $b are to be added")
      }
    }
  }
}



data class TimesFun<T1,T2,T3>(
    val leftAnnihilator: T1,
    val rightAnnihilator: T2,
    val resultType: LType<T3>, // (PType<T1>, PType<T2>) -> PType<T3>
    val times: (T1, T2) -> T3
) {
  val resultZero: T3 = times(leftAnnihilator, rightAnnihilator)
  fun verifyAnnihilator(a: T1 = leftAnnihilator, b: T2 = rightAnnihilator) {
    check(times(a,rightAnnihilator) == resultZero && times(leftAnnihilator,b) == resultZero)
    { "Value $a and $b violate the annihilator requirement of times for annihilators $leftAnnihilator and $rightAnnihilator" }
  }

  companion object {
    /** Wraps a function to have these annihilators. */
    inline fun <T1, T2, T3> withAnnihilators(
        leftAnnihilator: T1, rightAnnihilator: T2,
        resultType: LType<T3>,
        crossinline timesFun: (T1, T2) -> T3
    ): TimesFun<T1, T2, T3> {
      val resultZero = timesFun(leftAnnihilator, rightAnnihilator)
      return TimesFun(leftAnnihilator, rightAnnihilator, resultType) { a, b ->
        if (a == leftAnnihilator || b == rightAnnihilator) resultZero else timesFun(a, b)
      }
    }

    /** Wraps a function to have null annihilators (with zero product property). */
    inline fun <T1, T2, T3> withNullAnnihilators(
        resultType: LType<T3?>,
        crossinline timesFun: (T1, T2) -> T3
    ): TimesFun<T1?, T2?, T3?> = TimesFun<T1?, T2?, T3?>(null, null, resultType) { a, b ->
      if (a == null || b == null) null else timesFun(a, b)
    }
  }
}

// use a map from Java class to most common PType for that class


// ======================= OPERATORS


sealed class NameTupleOp(
    val resultSchema: NameSchema
) {
  abstract fun run(): Iterator<NameTuple>

  data class Ext(
      val parent: NameTupleOp,
      /** This can also be a [MapFun] */
      val extFun: ExtFun
  ): NameTupleOp(NameSchema(
      keys = parent.resultSchema.keys + extFun.extSchema.keys,
      vals = extFun.extSchema.vals
  )) {
//    companion object {
//      fun runExtFunctionOnDefaultValues(ps: NameSchema, f: ExtFun): List<ValAttribute<*>> {
//        val tuple = (ps.keys.map { it.name to it.type.examples.first() } +
//            ps.vals.map { it.name to it.default }).toMap()
//        val result = f.extFun(tuple)
//        if (result.isEmpty()) {
//          require()
//        }
//        f.extVals.map { va ->
//          require(va.name in result)
//        }
//      }
//    }
    val parentKeyNames = parent.resultSchema.keys.map { it.name }

    override fun run(): Iterator<NameTuple> {
      return ExtIterator()
    }

    inner class ExtIterator : Iterator<NameTuple> {
      val iter = parent.run()
      var top = findTop()


      fun findTop(): Iterator<NameTuple> {
        if (!iter.hasNext())
          return Collections.emptyIterator()
        var topIter: Iterator<NameTuple>
        var topParent: NameTuple
        do {
          topParent = iter.next()
          topIter = extFun.extFun(topParent).iterator()
        } while (iter.hasNext() && !topIter.hasNext())
        return PrependKeysIteraor(parentKeyNames, topParent, topIter)
      }

      override fun hasNext(): Boolean = top.hasNext()
      override fun next(): NameTuple {
        val r = top.next()
        if (!top.hasNext()) top = findTop()
        return r
      }

    }

    class PrependKeysIteraor(
        keysToPrepend: List<String>,
        parent: NameTuple,
        val iter: Iterator<NameTuple>
    ) : Iterator<NameTuple> {
      val parentKeys = parent.filterKeys { it in keysToPrepend }
      override fun hasNext(): Boolean = iter.hasNext()
      override fun next(): NameTuple {
        val n = iter.next().filterKeys { it !in parentKeys }
//        check(parentKeys.keys.all { it !in n }) {"the tuple resulting from this ext emitted a key that is present in the parent keys. Tuple: $n. ParentKeys: $parentKeys"}
        return parentKeys + n
      }
    }
  }

  data class Load(
      val table: String,
      val schema: NameSchema,
      val iter: Iterator<NameTuple> = Collections.emptyIterator()
  ): NameTupleOp(schema) {
//    constructor(table: String, schema: NameSchema, iter: Iterator<NameTuple>): this(table, schema, Collections.emptyIterator())
    override fun run(): Iterator<NameTuple> = iter
  }

  data class Empty(
      val schema: NameSchema
  ) : NameTupleOp(schema) {
    override fun run(): Iterator<NameTuple> = Collections.emptyIterator()
  }


  /**
   * Restricted to two parents. Future work could extend this to any number of parents.
   */
  sealed class MergeUnion0(
      val p1: NameTupleOp,
      val p2: NameTupleOp,
      plusFuns0: Map<Name, PlusFun<*>>
  ): NameTupleOp(NameSchema(
      keys = intersectKeys(p1.resultSchema.keys,p2.resultSchema.keys),
      vals = unionValues(p1.resultSchema.vals,p2.resultSchema.vals)
  )) {
    init {
      require(resultSchema.vals.map(ValAttribute<*>::name).containsAll(plusFuns0.keys)) {"plus functions provided for values that do not exist"}
      plusFuns0.forEach { name, pf ->
        val d = resultSchema.vals.find { it.name == name }!!.default
        pf.verifyIdentity()
        require(pf.identity == d) {"plus function for $name does not match identity of parent: $d"}
      }
    }

    val plusFuns: Map<Name, PlusFun<*>> = resultSchema.vals.map { va ->
      val pf = plusFuns0[va.name] ?: PlusFun.plusErrorFun(va.default)
      va.name to pf
    }.toMap()

    override fun toString(): String {
      return "MergeUnion(p1=$p1, p2=$p2, plusFuns=$plusFuns)"
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other?.javaClass != javaClass) return false

      other as MergeUnion

      if (p1 != other.p1) return false
      if (p2 != other.p2) return false
      if (plusFuns != other.plusFuns) return false

      return true
    }

    override fun hashCode(): Int {
      var result = p1.hashCode()
      result = 31 * result + p2.hashCode()
      result = 31 * result + plusFuns.hashCode()
      return result
    }

    companion object {
      /**
       * If A has access path (c,a) and B has access path (c,b),
       * then MergeUnion(A,B) has access path (c).
       */
      private fun intersectKeys(a: List<Attribute<*>>, b: List<Attribute<*>>): List<Attribute<*>> {
        var i = 0
        val minSize = Math.min(a.size,b.size)
        val c: MutableList<Attribute<*>> = ArrayList(minSize)
        while (i < minSize && a[i].name == b[i].name) {
          require(a[i] == b[i]) {"MergeUnion: matching keys ${a[i].name} has different types in parents: ${a[i].type} and ${b[i].type}"}
          c += a[i]
          i++
        }
        // make sure no more keys match
        require((a.subList(i,a.size) + b.subList(i,b.size)).map(Attribute<*>::name).let { it.size == it.toSet().size })
          {"MergeUnion: key attributes $a and $b have matching keys that are not in their common prefix"}
        return c
      }
      /**
       * Union maps by key. Check that entries with the same key have the same value.
       */
      private fun unionValues(a: List<ValAttribute<*>>, b: List<ValAttribute<*>>): List<ValAttribute<*>> {
        return a + b.filter { bv ->
          val av = a.find { it.name == bv.name }
          if (av != null) {
            require(av == bv) // calls equals() method
            {"MergeUnion: value attributes $a and $b have an attribute with the same name but different types"}
            false
          } else true
        }
      }
    }

    override fun run(): Iterator<NameTuple> {
      return MergeUnionIterator(resultSchema.keys, Iterators.peekingIterator(p1.run()),
          Iterators.peekingIterator(p2.run()), plusFuns)
    }

    class MergeUnionIterator(
        val keys: List<Attribute<*>>,
        val i1: PeekingIterator<NameTuple>,
        val i2: PeekingIterator<NameTuple>,
        val plusFuns: Map<Name, PlusFun<*>>
    ) : Iterator<NameTuple> {
      val comparator = KeyComparator(keys)
      val keysAndValues = keys.map { it.name } + plusFuns.keys
      val keyNames = keys.map { it.name }
      var old: NameTuple = keys.map { it.name to it.type.examples.first() }.toMap()

      override fun hasNext(): Boolean {
        return i1.hasNext() || i2.hasNext()
      }

      fun getCompare(): Int = when {
        i1.hasNext() && i2.hasNext() -> comparator.compare(i1.peek(), i2.peek())
        i1.hasNext() -> -1
        i2.hasNext() -> 1
        else -> throw NoSuchElementException()
      }.let { Integer.signum(it) }

      override fun next(): NameTuple {
        var c = getCompare()
        val old = if (c == 1) i2.peek() else i1.peek()
        var cur = old

        // first iteration: set result to the values from i1 or i2
        var result = when (c) {
          -1 -> putDefault(i1.next())
          1 -> putDefault(i2.next())
          else -> addValues(i1.next(), i2.next())
        }

        if (hasNext()) {
          c = getCompare()
          cur = if (c == 1) i2.peek() else i1.peek()

          while (comparator.compare(old, cur) == 0) {
            // add the current matching values into the result
            result = addValues(result,
                when (c) {
                  -1 -> putDefault(i1.next())
                  1 -> putDefault(i2.next())
                  else -> addValues(i1.next(), i2.next())
                })
            if (!hasNext()) break
            c = getCompare()
            cur = if (c == 1) i2.peek() else i1.peek()
          }
        }
        return result + old.filterKeys { it in keyNames }
      }

      private fun putDefault(t: NameTuple): NameTuple {
        return plusFuns.mapValues { (name,f) ->
          if (name in t) t[name]!!
          else f.identity
        }
      }

      private fun addValues(t1: NameTuple, t2: NameTuple): NameTuple {
        return plusFuns.mapValues { (name,f) ->
          @Suppress("UNCHECKED_CAST")
          when {
            name in t1 && name in t2 -> (f.plus as (Any?,Any?) -> Any?)(t1[name], t2[name])
            name in t1 -> t1[name]
            name in t2 -> t2[name]
            else -> f.identity
          }
        }
      }
    }


    class MergeUnion(
        p1: NameTupleOp,
        p2: NameTupleOp,
        plusFuns0: Map<Name, PlusFun<*>>
    ) : MergeUnion0(p1,p2,plusFuns0)

    class MergeAgg(
        p: NameTupleOp,
        val keysKept: Collection<Name>,
        plusFuns0: Map<Name, PlusFun<*>>
    ) : MergeUnion0(p,
        p2 = Empty(NameSchema(p.resultSchema.keys.filter { it.name in keysKept }, listOf())),
        plusFuns0 = plusFuns0) {
      override fun toString(): String {
        return "MergeAgg(p=$p1, keysKept=$keysKept, plusFuns=$plusFuns)"
      }
    }
  }

  data class Rename(
      val p: NameTupleOp,
      val renameMap: Map<Name,Name>
  ) : NameTupleOp(p.resultSchema.let { NameSchema(
      it.keys.map { attr -> renameMap[attr.name]?.let { attr.withNewName(it) } ?: attr },
      it.vals.map { attr -> renameMap[attr.name]?.let { attr.withNewName(it) } ?: attr }
  ) }) {
    override fun run(): Iterator<NameTuple> {
      val iter = p.run()
      return object : AbstractIterator<NameTuple>() {
        override fun computeNext() {
          if (!iter.hasNext()) {
            done()
          } else {
            val n = iter.next().mapKeys { (k,_) ->
              if (k in renameMap) renameMap[k]!! else k
            }
            setNext(n)
          }
        }
      }

    }
  }

  data class Sort(
      val p: NameTupleOp,
      val newSort: List<Name>
  ) : NameTupleOp(NameSchema(
      newSort.apply { require(this.toSet() == p.resultSchema.keys.map { it.name }.toSet()) {"not all names re-sorted: $newSort on ${p.resultSchema}"} }
          .map { name -> p.resultSchema.keys.find{it.name == name}!! },
      p.resultSchema.vals
  )) {
    override fun run(): Iterator<NameTuple> {
      val l: MutableList<NameTuple> = ArrayList()
      p.run().forEach { l += it }
      l.sortWith(KeyComparator(resultSchema.keys))
      return l.iterator()
    }
  }


  class KeyComparator(
      val keys: List<Attribute<*>>
  ) : Comparator<NameTuple> {
    override fun compare(p1: NameTuple, p2: NameTuple): Int {
      var c: Int = 0
      for (ka in keys) {
        @Suppress("UNCHECKED_CAST")
        c = (ka.type as Comparator<Any?>).compare(p1[ka.name], p2[ka.name])
        if (c != 0) return c
      }
      return c
    }
  }

  data class MergeJoin(
      val p1: NameTupleOp,
      val p2: NameTupleOp,
      val timesFuns: Map<Name,TimesFun<*,*,*>>
  ): NameTupleOp(NameSchema(
      keys = unionKeys(p1.resultSchema.keys,p2.resultSchema.keys),
      vals = intersectValues(p1.resultSchema.vals,p2.resultSchema.vals, timesFuns)
  )) {
    companion object {

      // similar to unionValues() in MergeUnion
      private fun unionKeys(a: List<Attribute<*>>, b: List<Attribute<*>>): List<Attribute<*>> {
        val commonIdxs = ArrayList<Int>(Math.min(a.size,b.size))
        val r = a + b.filter { bv ->
          val avidx = a.indexOfFirst { it.name == bv.name }
          if (avidx != -1) {
            require(a[avidx] == bv) // calls equals() method
            {"MergeJoin: key attributes $a and $b have an attribute with the same name but different types"}
            commonIdxs.add(avidx)
            false
          } else true
        }
        commonIdxs.sort()
        val x = Array(commonIdxs.size) {it}.toList()
        require(commonIdxs == x) {"some common key attributes of this MergeJoin are not in the prefix: $commonIdxs, $x, $a, $b"}
        return r
      }

      private fun intersectValues(a: List<ValAttribute<*>>, b: List<ValAttribute<*>>,
                                  timesFuns: Map<Name, TimesFun<*, *, *>>): List<ValAttribute<*>> {
        val res = a.filter { attr -> b.any { it.name == attr.name } }
            .map { attr ->
              require(attr.name in timesFuns) {"no times operator for matching value attributes $attr"}
              val battr = b.find { it.name == attr.name }!!
              val times: TimesFun<*, *, *> = timesFuns[attr.name]!!
              require(attr.default == times.leftAnnihilator)
              {"for attribute ${attr.name}, left default value ${attr.default} != times fun left annihilator ${times.leftAnnihilator}"}
              require(battr.default == times.rightAnnihilator)
              {"for attribute ${attr.name}, right default value ${battr.default} != times fun right annihilator ${times.rightAnnihilator}"}
//              ValAttribute(attr.name, times.resultType, times.resultZero)
              multiplyTypeGet(attr.name, times)
            }
        require(timesFuns.size == res.size) {"mismatched number of times functions provided, $timesFuns for result value attributes $res"}
        return res
      }
      private fun <T1,T2,T3> multiplyTypeGet(name: Name, times: TimesFun<T1,T2,T3>) = ValAttribute<T3>(
          name,
          times.resultType,
          times.resultZero
      )

    }

    override fun run(): Iterator<NameTuple> {
      return MergeJoinIterator(p1.resultSchema.keys.intersect(p2.resultSchema.keys).toList(),
          p1.resultSchema.keys.map { it.name }, p2.resultSchema.keys.map { it.name },
          Iterators.peekingIterator(p1.run()),
          Iterators.peekingIterator(p2.run()), timesFuns)
    }

    data class MergeJoinIterator(
        val keys: List<Attribute<*>>, // common keys
        val p1keys: List<Name>,
        val p2keys: List<Name>,
        val i1: PeekingIterator<NameTuple>,
        val i2: PeekingIterator<NameTuple>,
        val timesFuns: Map<Name, TimesFun<*,*,*>>
    ) : Iterator<NameTuple> {

      val comparator = KeyComparator(keys)
      var topIter: PeekingIterator<NameTuple> = findTop()

      class OneRowIterator<T>(val rowComparator: Comparator<T>,
                              private val iter: PeekingIterator<T>) : PeekingIterator<T> by iter {
        val firstTuple: T? = if (iter.hasNext()) iter.peek() else null

        override fun next(): T = if (hasNext()) iter.next() else throw NoSuchElementException("the iterator is past the original row $firstTuple")

        override fun hasNext(): Boolean = iter.hasNext() && rowComparator.compare(firstTuple, iter.peek()) == 0

        override fun peek(): T = if (hasNext()) iter.peek() else throw NoSuchElementException("the iterator is past the original row $firstTuple")
      }
      fun readRow(
          /** See [TupleComparatorByKeyPrefix] */
          rowComparator: Comparator<NameTuple>,
          iter: PeekingIterator<NameTuple>
      ): List<NameTuple> {
        check(iter.hasNext()) {"$iter should hasNext()"}
        val first = iter.peek()
        val list = LinkedList<NameTuple>()
        do {
          list.add(iter.next())
        } while (iter.hasNext() && rowComparator.compare(first, iter.peek()) == 0)
        return list
      }

      fun findTop(): PeekingIterator<NameTuple> {
        loop@while (i1.hasNext() && i2.hasNext()) {
          val c = comparator.compare(i1.peek(), i2.peek())
          when (Integer.signum(c)) {
            -1 -> i1.next()
            1 -> i2.next()
            else -> break@loop
          }
        }
        if (!i1.hasNext() || !i2.hasNext()) return Iterators.peekingIterator(Collections.emptyIterator())
        // We are either aligned or out of data on at least one iterator
        val one1 = OneRowIterator(comparator, i1)
        val one2 = readRow(comparator, i2)
        return Iterators.peekingIterator(CartesianIterator(one1, one2, this::times)) // must have at least one entry, but maybe it is the default entry
      }




      override fun hasNext(): Boolean {
        return topIter.hasNext()
      }

      override fun next(): NameTuple {
        val r: NameTuple = topIter.next()
        if (!topIter.hasNext())
          topIter = findTop()
        return r
      }

      private fun times(t1: NameTuple, t2: NameTuple): NameTuple {
        return timesFuns.mapValues { (name,f) ->
          @Suppress("UNCHECKED_CAST")
          when {
            name in t1 && name in t2 -> (f.times as (Any?,Any?) -> Any?)(t1[name], t2[name]) // we should always have this case
            name in t1 -> t1[name]
            name in t2 -> t2[name]
            else -> f.resultZero
          }
        } + t1.filterKeys { it in p1keys } + t2.filterKeys { it in p2keys }
      }

      class CartesianIterator(
          private val firstIter: PeekingIterator<NameTuple>,
          private val secondIterable: Iterable<NameTuple>,
          private val multiplyOp: (NameTuple, NameTuple) -> NameTuple
      ) : Iterator<NameTuple> {
        private var secondIter: PeekingIterator<NameTuple> = Iterators.peekingIterator(secondIterable.iterator())

        init {
          if (!firstIter.hasNext() || !secondIter.hasNext()) {
            while (firstIter.hasNext()) firstIter.next()
          }
        }

        /*
        1. scan left until we find a position where hasNext() is true. If all are false then terminate.
        2. advance that iterator at the position and fill in curTuples
        3. reset all iterators to the right and fill in curTuples
         */

        override fun hasNext(): Boolean {
          return firstIter.hasNext() && secondIter.hasNext()
        }

        override fun next(): NameTuple {
          val ret = multiplyOp(firstIter.peek(), secondIter.next())
          prepNext()
          return ret
        }

        private fun prepNext() {
          if (!secondIter.hasNext()) {
            firstIter.next()
            if (!firstIter.hasNext())
              return
            secondIter = Iterators.peekingIterator(secondIterable.iterator())
          }
        }
      }


    }
  }


  data class ScanFromData(
      val schema: NameSchema,
      val iter: Iterable<NameTuple>
  ) : NameTupleOp(schema) {
    override fun run(): Iterator<NameTuple> = iter.iterator()
  }

}




///* First lower to keep the names with the scheams. Then erase the names.
// */
//
//interface PosSchema {
//  val names: List<Name>
//  val types: List<Attribute<*>>
//}
//interface PosTuple {
//  val attrs: List<*>
//}
