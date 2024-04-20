package PIC

import chisel3._ // VecInit
import chisel3.util._ // MuxCase
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._ // LazyModule
import freechips.rocketchip.rocket._ // cache 接口
import freechips.rocketchip.tilelink._
import freechips.rocketchip.regmapper._

//  +-+-+-+ +-+-+-+-+-+-+ +-+-+-+-+-+-+-+
//  |T|h|e| |m|o|d|u|l|e| |r|e|s|i|d|e|s|
//  +-+-+-+-+-+-+-+-+-+-+ +-+-+-+-+-+-+-+
//  |t|i|l|e| |s|i|d|e|                  
//  +-+-+-+-+ +-+-+-+-+  

class translate_req extends Bundle {
  val vaddr = UInt(32.W) // TODO magic number
}

class translate_resp extends Bundle {
  val paddr = UInt(32.W) // TODO magic number
}


class TLB_SlaveCtl(sys_configs:Sys_Config)(implicit p: Parameters) extends LazyModule
{

    val mm_addr=sys_configs.query_tlb_MMIO_bigInt
    println("TLB receiver addr =",mm_addr)
    val device = new SimpleDevice("TLB_SlavePort", Seq("from_cbus,receive_and_reslove_TLB_req"))
    val tlb_slave_node = TLRegisterNode(
        address = Seq(AddressSet(mm_addr, 0xFF)),
        device = device,
        beatBytes = 64,
        concurrency = 1)

    lazy val module = new TLB_SlaveCtl_Impl
    class TLB_SlaveCtl_Impl(implicit p: Parameters) extends LazyModuleImp(this)
    {  
        val io=IO(new Bundle{
            // receive query request
            val trans_req=Decoupled(new translate_req())
            // send back response
            val trans_resp=Flipped(Decoupled(new translate_resp()))
        })

        val (tlb_slave_tl, tlb_slave_edge) = tlb_slave_node.in(0)
        val vaddr_reg = RegInit(0.U(32.W))
        tlb_slave_node.regmap(
            0x00 -> Seq(RegField(64, vaddr_reg)),
        )

        io.trans_req.valid:=false.B
        io.trans_req.bits.vaddr:=vaddr_reg
        io.trans_resp.ready:=false.B

        val proc_idle :: query_tlb :: wait_tlb ::return_paddr :: Nil = Enum(4)
        val cmd_proc_state = RegInit(proc_idle)

        val paddr_reg = RegInit(0.U(32.W))

        tlb_slave_tl.a.ready:=false.B
        tlb_slave_tl.d.valid := false.B

        switch(cmd_proc_state)
        {
            is(proc_idle)
            {
                tlb_slave_tl.a.ready:=true.B
                when(tlb_slave_tl.a.fire)
                {
                    cmd_proc_state:=query_tlb
                    vaddr_reg:=tlb_slave_tl.a.bits.data
                }
            }
            is(query_tlb)
            {
                io.trans_req.valid:=true.B
                when(io.trans_req.fire)
                {
                    io.trans_req.bits.vaddr:=vaddr_reg
                    cmd_proc_state:=wait_tlb
                }
            }
            is(wait_tlb)
            {
                io.trans_resp.ready:=true.B
                when(io.trans_resp.fire)
                {
                    paddr_reg:=io.trans_resp.bits.paddr
                    cmd_proc_state:=return_paddr
                }
            }
            is(return_paddr)
            {
                tlb_slave_tl.d.valid := true.B
                when(tlb_slave_tl.d.fire)
                {
                    tlb_slave_tl.d.bits.data:=paddr_reg
                    cmd_proc_state:=proc_idle
                }
            }
        }

    }
}

