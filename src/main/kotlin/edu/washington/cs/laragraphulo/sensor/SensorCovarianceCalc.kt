package edu.washington.cs.laragraphulo.sensor

import com.google.common.collect.Iterators
import edu.mit.ll.graphulo.DynamicIteratorSetting
import edu.mit.ll.graphulo.Graphulo
import edu.mit.ll.graphulo.apply.ApplyIterator
import edu.mit.ll.graphulo.apply.ApplyOp
import edu.mit.ll.graphulo.reducer.ReducerSerializable
import edu.mit.ll.graphulo.simplemult.MathTwoScalar
import edu.mit.ll.graphulo.skvi.DebugInfoIterator
import edu.washington.cs.laragraphulo.util.GraphuloUtil
import org.apache.accumulo.core.client.Connector
import org.apache.accumulo.core.client.IteratorSetting
import org.apache.accumulo.core.client.lexicoder.DoubleLexicoder
import org.apache.accumulo.core.client.lexicoder.PairLexicoder
import org.apache.accumulo.core.client.lexicoder.ULongLexicoder
import org.apache.accumulo.core.client.security.tokens.PasswordToken
import org.apache.accumulo.core.data.ByteSequence
import org.apache.accumulo.core.data.Key
import org.apache.accumulo.core.data.Range
import org.apache.accumulo.core.data.Value
import org.apache.accumulo.core.iterators.Combiner
import org.apache.accumulo.core.iterators.IteratorEnvironment
import org.apache.accumulo.core.iterators.OptionDescriber
import org.apache.accumulo.core.iterators.SortedKeyValueIterator
import org.apache.accumulo.core.util.ComparablePair
import org.apache.hadoop.io.Text
import java.io.IOException
import java.util.*

class SensorCovarianceCalc(
    val conn: Connector,
    val pw: PasswordToken,
    val sensorA: String,
    val sensorB: String,
    val doString: Boolean = true
) {
  val sensorX = "${sensorA}_${sensorB}_X"
  val sensorU = "${sensorA}_${sensorB}_U"
  val sensorC = "${sensorA}_${sensorB}_C"

//  enum class Sensor { A, B }

  private fun recreate(vararg tns: String) {
    tns.forEach { tn ->
      if (conn.tableOperations().exists(tn))
        conn.tableOperations().delete(tn)
      conn.tableOperations().create(tn)
    }
  }

  fun binAndDiff() {
    require(conn.tableOperations().exists(sensorA)) {"table $sensorA does not exist"}
    require(conn.tableOperations().exists(sensorB)) {"table $sensorB does not exist"}
    recreate(sensorX)

    val G = Graphulo(conn, pw)

    val itersBefore: List<IteratorSetting> = DynamicIteratorSetting(21, "bin")
        .append(BinRowApply.iteratorSetting(1, doString))
        .append(AppendCounterApply.iteratorSetting(1, doString))
        .append(CombineSumCnt.iteratorSetting(1, doString))
        .append(DividePairApply.iteratorSetting(1, doString))
//        .append(IteratorSetting(1, DebugInfoIterator::class.java))
        .iteratorSettingList
    val subtract = MathTwoScalar::class.java
    val subtractOptions = MathTwoScalar.optionMap(MathTwoScalar.ScalarOp.MINUS, MathTwoScalar.ScalarType.DOUBLE, null, true)
    val tCounter = RowCountReduce()
    tCounter.init(emptyMap<String,String>().toMutableMap(), null)

    G.TwoTableEWISE(sensorA, sensorB, null, sensorX,
        -1, subtract, subtractOptions,
        null, null, null, null, false, false,
        itersBefore, itersBefore, /*Collections.singletonList(IteratorSetting(1, DebugInfoIterator::class.java))*/null, tCounter, null, -1, null, null)
    val tCount = tCounter.serializableForClient
    println("tCount is $tCount")
  }

}




/**
 * The row contains the measurement time.
 * Bin to the nearest 60 seconds (=60000 ms) according to:
 *
 * > def $\operatorname{bin}(t)=t-\operatorname{mod}(t,60)+60 \left\lfloor \frac{\operatorname{mod}(t,60)}{60}+.5 \right\rfloor$
 */
class BinRowApply : ApplyOp {
  /** Whether to use string encoding or to use ulong encoding. */
  var t_string: Boolean = true

  val binSize = 60000

  companion object {
    val T_STRING = "T_STRING"
    private val ULL = ULongLexicoder()

    fun iteratorSetting(priority: Int, t_string: Boolean): IteratorSetting {
      val itset = IteratorSetting(priority, ApplyIterator::class.java)
      itset.addOption(ApplyIterator.APPLYOP, BinRowApply::class.java.name)
      if (!t_string)
        itset.addOption(ApplyIterator.APPLYOP + GraphuloUtil.OPT_SUFFIX + T_STRING, t_string.toString())
      return itset
    }
  }

  override fun init(options: MutableMap<String, String>, env: IteratorEnvironment?) {
    if (options.containsKey(T_STRING))
      t_string = options[T_STRING]!!.toBoolean()
  }

  override fun seekApplyOp(range: Range?, columnFamilies: MutableCollection<ByteSequence>?, inclusive: Boolean) {
  }

  override fun apply(k: Key, v: Value): MutableIterator<MutableMap.MutableEntry<Key, Value>> {
    val t = k.rowData.toArray().let { if (t_string) String(it).toLong() else ULL.decode(it) }
    val tm = t % binSize
    val tb = t - tm + (if (tm >= binSize/2) binSize else 0) // new t
    val rb = if (t_string) tb.toString().toByteArray() else ULL.encode(tb) // new row
    val kb = Key(rb, k.columnFamilyData.toArray(), k.columnQualifierData.toArray(), k.columnVisibility.bytes) // no ts
    return Iterators.singletonIterator(AbstractMap.SimpleImmutableEntry(kb,v))
  }
}

/**
 * Append ",1" or a ULong 1 to the Value.
 * Used before combining sum,cnt toward computing average.
 */
class AppendCounterApply : ApplyOp {
  /** Whether to use string encoding or to use ulong encoding. */
  var t_string: Boolean = true

  companion object {
    val T_STRING = "T_STRING"
    private val dlex = DoubleLexicoder()
    private val lex = PairLexicoder(DoubleLexicoder(), ULongLexicoder())
    private val commaOne = ",1".toByteArray()

    fun iteratorSetting(priority: Int, t_string: Boolean): IteratorSetting {
      val itset = IteratorSetting(priority, ApplyIterator::class.java)
      itset.addOption(ApplyIterator.APPLYOP, AppendCounterApply::class.java.name)
      if (!t_string)
        itset.addOption(ApplyIterator.APPLYOP + GraphuloUtil.OPT_SUFFIX + T_STRING, t_string.toString())
      return itset
    }
  }

  override fun init(options: MutableMap<String, String>, env: IteratorEnvironment?) {
    if (options.containsKey(T_STRING))
      t_string = options[T_STRING]!!.toBoolean()
  }

  override fun seekApplyOp(range: Range?, columnFamilies: MutableCollection<ByteSequence>?, inclusive: Boolean) {
  }

  override fun apply(k: Key, v: Value): MutableIterator<MutableMap.MutableEntry<Key, Value>> {
    if (t_string) {
      val varr = v.get()
      val sz = varr.size
      val nvarr = Arrays.copyOf(varr, sz+commaOne.size)
      System.arraycopy(commaOne, 0, nvarr, sz, commaOne.size)
      return Iterators.singletonIterator(AbstractMap.SimpleImmutableEntry(k,Value(nvarr)))
    } else {
      val nv = lex.encode(ComparablePair(dlex.decode(v.get()), 1L))
      return Iterators.singletonIterator(AbstractMap.SimpleImmutableEntry(k,Value(nv)))
    }
  }
}

/**
 * Combine via sum and cnt. Interpret as Double, with count as ULong.
 * When encoding as String, must be of the form "5.6,3".
 * When encoding as numbers, must be of the form Pair<Double,ULong>
 * Used for computing average.
 */
class CombineSumCnt : Combiner() {
  /** Whether to use string encoding or to use numeric encoding. */
  var t_string: Boolean = true

  override fun reduce(key: Key, iter: Iterator<Value>): Value {
    var sum = 0.0
    var count: Long = 0L

    if (!iter.hasNext()) return Value()
    var next: Value? = iter.next()
    if (!iter.hasNext()) return next!!

    do {
      if (t_string) {
        val stats = next!!.toString().split(',') //.dropLastWhile(String::isEmpty).toTypedArray()
        require(stats.size == 2) {"bad value: $next"}
        sum += stats[0].toDouble()
        count += stats[1].toLong()
      } else {
        val pair = lex.decode(next!!.get())
        sum += pair.first
        count += pair.second
      }
      next = if (iter.hasNext()) iter.next() else null
    } while (next != null)

    val ret = if (t_string) "$sum,$count".toByteArray() else lex.encode(ComparablePair(sum, count))
    return Value(ret)
  }

  @Throws(IOException::class)
  override fun init(source: SortedKeyValueIterator<Key, Value>, options: Map<String, String>, env: IteratorEnvironment?) {
    super.init(source, options, env)
    if (options.containsKey(T_STRING))
      t_string = options[T_STRING]!!.toBoolean()
  }

  override fun describeOptions(): OptionDescriber.IteratorOptions {
    val io = super.describeOptions()
    io.setName("CombineSumCnt")
    io.setDescription("Combiner that keeps track of sum and count of pairs of doubles and longs")
    io.addNamedOption(T_STRING, "True for String encoding, false for pair lexicoding (default true)")
    return io
  }

  companion object {
    val T_STRING = "T_STRING"
    private val lex = PairLexicoder(DoubleLexicoder(), ULongLexicoder())

    fun iteratorSetting(priority: Int, t_string: Boolean = true,
                        columns: List<IteratorSetting.Column> = Collections.emptyList()): IteratorSetting {
      val itset = IteratorSetting(priority, CombineSumCnt::class.java)
      if (columns.isEmpty())
        Combiner.setCombineAllColumns(itset, true)
      else
        Combiner.setColumns(itset, columns)
      if (!t_string)
        itset.addOption(T_STRING, t_string.toString())
      return itset
    }
  }

}


/**
 * Divides value interpreted as a pair, `first / second`.
 */
class DividePairApply : ApplyOp {
  /** Whether to use string encoding or to use numeric encoding. */
  var t_string: Boolean = true

  companion object {
    val T_STRING = "T_STRING"
    private val dlex = DoubleLexicoder()
    private val lex = PairLexicoder(DoubleLexicoder(), ULongLexicoder())

    fun iteratorSetting(priority: Int, t_string: Boolean): IteratorSetting {
      val itset = IteratorSetting(priority, ApplyIterator::class.java)
      itset.addOption(ApplyIterator.APPLYOP, DividePairApply::class.java.name)
      if (!t_string)
        itset.addOption(ApplyIterator.APPLYOP + GraphuloUtil.OPT_SUFFIX + T_STRING, t_string.toString())
      return itset
    }
  }

  override fun init(options: MutableMap<String, String>, env: IteratorEnvironment?) {
    if (options.containsKey(T_STRING))
      t_string = options[T_STRING]!!.toBoolean()
  }

  override fun seekApplyOp(range: Range?, columnFamilies: MutableCollection<ByteSequence>?, inclusive: Boolean) {
  }

  override fun apply(k: Key, v: Value): MutableIterator<MutableMap.MutableEntry<Key, Value>> {
    if (t_string) {
      val stats = v.toString().split(',')
      require(stats.size == 2) {"bad value: $v"}
      val sum = stats[0].toDouble()
      val cnt = stats[1].toLong()
      val nv = (sum / cnt).toString().toByteArray()
      return Iterators.singletonIterator(AbstractMap.SimpleImmutableEntry(k,Value(nv)))
    } else {
      val pair = lex.decode(v.get())
      val sum = pair.first
      val cnt = pair.second
      val nv = dlex.encode(sum / cnt)
      return Iterators.singletonIterator(AbstractMap.SimpleImmutableEntry(k,Value(nv)))
    }
  }
}






/**
 * Count the unique rows. Assumes rows given in order.
 */
class RowCountReduce : ReducerSerializable<Long>() {
  var count = 0L
  var lastRow = Text()

  override fun combine(another: Long) {
    count += another
  }

  override fun getSerializableForClient(): Long = count

  override fun hasTopForClient(): Boolean = count != 0L

  override fun init(options: MutableMap<String, String>, env: IteratorEnvironment?) {
  }

  override fun reset() {
    count = 0
  }

  override fun update(k: Key, v: Value) {
    if (k.compareRow(lastRow) == 0) return
    count++
    lastRow = k.getRow(lastRow)
  }
}





