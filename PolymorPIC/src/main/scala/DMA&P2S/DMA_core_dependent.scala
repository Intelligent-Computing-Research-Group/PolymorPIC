package form_icenet

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp, IdRange}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.tilelink._

class StreamIO(val w: Int) extends Bundle {
  val in = Flipped(Decoupled(new StreamChannel(w)))
  val out = Decoupled(new StreamChannel(w))

  def flipConnect(other: StreamIO) {
    in <> other.out
    other.in <> out
  }
}

class StreamReadRequest extends Bundle {
  val address = UInt(48.W)
  val length = UInt(15.W)
  val partial = Bool()
}

class StreamChannel(val w: Int) extends Bundle {
  val data = UInt(w.W)
  val keep = UInt((w/8).W)
  val last = Bool()
}

class ReservationBufferAlloc(nXacts: Int, nWords: Int) extends Bundle {
  private val xactIdBits = log2Ceil(nXacts)
  private val countBits = log2Ceil(nWords + 1)

  val id = UInt(xactIdBits.W)
  val count = UInt(countBits.W)

}

class ReservationBufferData(nXacts: Int, dataBits: Int) extends Bundle {
  private val xactIdBits = log2Ceil(nXacts)

  val id = UInt(xactIdBits.W)
  val data = new StreamChannel(dataBits)

}

class ReservationBuffer(nXacts: Int, nWords: Int, dataBits: Int) extends Module {
  private val xactIdBits = log2Ceil(nXacts)
  private val countBits = log2Ceil(nWords + 1)

  require(nXacts <= nWords)

  val io = IO(new Bundle {
    val alloc = Flipped(Decoupled(new ReservationBufferAlloc(nXacts, nWords)))
    val in = Flipped(Decoupled(new ReservationBufferData(nXacts, dataBits)))
    val out = Decoupled(new StreamChannel(dataBits))
  })

  def incWrap(cur: UInt, inc: UInt): UInt = {
    val unwrapped = cur +& inc
    Mux(unwrapped >= nWords.U, unwrapped - nWords.U, unwrapped)
  }

  val buffer = Module(new BufferBRAM(nWords, new StreamChannel(dataBits)))
  val bufValid = RegInit(0.U(nWords.W))

  val head = RegInit(0.U(countBits.W))
  val tail = RegInit(0.U(countBits.W))
  val count = RegInit(0.U(countBits.W))

  val full = (count + io.alloc.bits.count) > nWords.U
  val xactHeads = Reg(Vec(nXacts, UInt(countBits.W)))
  val curXactHead = xactHeads(io.in.bits.id)

  val occupied = RegInit(false.B)
  val ren = (!occupied || io.out.ready) && (bufValid >> tail)(0)

  count := count +
              Mux(io.alloc.fire, io.alloc.bits.count, 0.U) -
              Mux(io.out.fire, 1.U, 0.U)
  bufValid := (bufValid | Mux(io.in.fire, UIntToOH(curXactHead), 0.U)) &
                         ~Mux(ren, UIntToOH(tail), 0.U)

  io.alloc.ready := !full
  io.in.ready := true.B
  io.out.valid := occupied
  io.out.bits := buffer.io.read.data

  buffer.io.write.en := io.in.fire
  buffer.io.write.addr := curXactHead
  buffer.io.write.data := io.in.bits.data
  buffer.io.read.en := ren
  buffer.io.read.addr := tail

  when (io.alloc.fire) {
    xactHeads(io.alloc.bits.id) := head
    head := incWrap(head, io.alloc.bits.count)
  }

  when (io.in.fire) {
    xactHeads(io.in.bits.id) := incWrap(curXactHead, 1.U)
  }

  when (io.out.fire) { occupied := false.B }

  when (ren) {
    occupied := true.B
    tail := incWrap(tail, 1.U)
  }
}

class Aligner(dataBits: Int) extends Module {
  val dataBytes = dataBits / 8

  val io = IO(new StreamIO(dataBits))

  val data = RegInit(0.U(dataBits.W))
  val keep = RegInit(0.U(dataBytes.W))
  val last = RegInit(false.B)
  val nbytes = RegInit(0.U(log2Ceil(dataBytes + 1).W))

  assert(!io.in.valid || io.in.bits.keep.orR,
    "Aligner cannot handle an empty flit")

  val rshift = PriorityEncoder(io.in.bits.keep)
  val full_keep = ((io.in.bits.keep >> rshift) << nbytes) | keep

  val in_mask = FillInterleaved(8, io.in.bits.keep)
  val in_data = io.in.bits.data & in_mask

  val rshift_bit = Cat(rshift, 0.U(3.W))
  val nbits = Cat(nbytes, 0.U(3.W))
  val bitmask = FillInterleaved(8, keep)
  val full_data = ((in_data >> rshift_bit) << nbits) | (data & bitmask)
  val full_nbytes = PopCount(full_keep)
  val fwd_last = io.in.bits.last && (full_keep >> dataBytes.U) === 0.U

  io.out.valid := (last && nbytes > 0.U) ||
                  (io.in.valid && (fwd_last || full_nbytes >= dataBytes.U))
  io.out.bits.data := Mux(last, data, full_data(dataBits-1, 0))
  io.out.bits.keep := Mux(last, keep, full_keep(dataBytes-1, 0))
  io.out.bits.last := last || fwd_last

  io.in.ready := full_nbytes < dataBytes.U ||
                 (io.out.ready && !last)

  when (io.in.fire && io.out.fire) {
    data := full_data >> dataBits.U
    keep := full_keep >> dataBytes.U
    last := io.in.bits.last && !fwd_last
    nbytes := Mux(fwd_last, 0.U, full_nbytes - dataBytes.U)
  } .elsewhen (io.in.fire) {
    data := full_data
    keep := full_keep
    last := io.in.bits.last
    nbytes := full_nbytes
  } .elsewhen (io.out.fire) {
    data := 0.U
    keep := 0.U
    last := false.B
    nbytes := 0.U
  }
}

class BufferBRAM[T <: Data](n: Int, typ: T) extends Module {
  val addrBits = log2Ceil(n)
  val io = IO(new Bundle {
    // The value in data becomes valid one cycle after enable is asserted.
    // The value is held so long as enable is false
    val read = new Bundle {
      val en = Input(Bool())
      val addr = Input(UInt(addrBits.W))
      val data = Output(typ)
    }
    val write = new Bundle {
      val en = Input(Bool())
      val addr = Input(UInt(addrBits.W))
      val data = Input(typ)
    }
  })

  val ram = Mem(n, typ)

  val wen = RegNext(io.write.en, false.B)
  val waddr = RegNext(io.write.addr)
  val wdata = RegNext(io.write.data)

  when (wen) { ram.write(waddr, wdata) }

  val rread_data = RegEnable(ram.read(io.read.addr), io.read.en)
  val rbypass = RegEnable(io.read.addr === waddr && wen, io.read.en)
  val rbypass_data = RegEnable(wdata, io.read.en)

  io.read.data := Mux(rbypass, rbypass_data, rread_data)
}