package PIC

import chisel3._ // VecInit
import chisel3.util._ // MuxCase
import freechips.rocketchip.tile._
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._ // LazyModule
import freechips.rocketchip.tilelink._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.rocket._

//  +-+-+-+ +-+-+-+-+-+-+ +-+-+-+-+-+-+-+
//  |T|h|e| |m|o|d|u|l|e| |r|e|s|i|d|e|s|
//  +-+-+-+-+-+-+-+-+-+-+ +-+-+-+-+-+-+-+
//  |t|i|l|e| |s|i|d|e|                  
//  +-+-+-+-+ +-+-+-+-+  

class PTW_query(sysCfg:Sys_Config)(implicit p: Parameters) extends LazyModule
{
    val mm_addr=sysCfg.query_tlb_MMIO_bigInt
    println("TLB receiver addr =",mm_addr)
    val device = new SimpleDevice("TLB_SlavePort", Seq("from_cbus,receive_and_reslove_TLB_req"))
    val tlb_slave_node = TLRegisterNode(
        address = Seq(AddressSet(mm_addr, 0xFF)),
        device = device,
        beatBytes = 64,
        concurrency = 1)

    lazy val module = new TLB_SlaveCtl_Impl
    class TLB_SlaveCtl_Impl(implicit p: Parameters) extends LazyModuleImp(this)
    with HasCoreParameters
    {  
        val io=IO(new Bundle{
            // ptw io
            val ptw = new TLBPTWIO
        })

        val (tlb_slave_tl, tlb_slave_edge) = tlb_slave_node.in(0)
        val vaddr_reg = RegInit(0.U(32.W))
        tlb_slave_node.regmap(
            0x00 -> Seq(RegField(64, vaddr_reg)),
        )

        io.ptw.customCSRs:=DontCare

        // ---------------
        // ptw visit logic
        // ---------------
        val req_addr = Reg(UInt(coreMaxAddrBits.W))
        val req_offset = req_addr(pgIdxBits - 1, 0)
        val req_vpn = req_addr(coreMaxAddrBits - 1, pgIdxBits)
        val pte = Reg(new PTE)

        val s_idle :: s_ptw_req :: s_ptw_resp :: s_resp :: Nil = Enum(4)
        val state = RegInit(s_idle)

        tlb_slave_tl.a.ready := (state === s_idle)

        when (tlb_slave_tl.a.fire()) {
            req_addr := tlb_slave_tl.a.bits.data
            state := s_ptw_req
        }

        private val ptw = io.ptw

        when (ptw.req.fire) { state := s_ptw_resp }

        when (state === s_ptw_resp && ptw.resp.valid) {
            pte := ptw.resp.bits.pte
            state := s_resp
        }

        when (tlb_slave_tl.d.fire) { state := s_idle }

        ptw.req.valid := (state === s_ptw_req)
        ptw.req.bits.valid := true.B
        ptw.req.bits.bits.addr := req_vpn
        // class PTWReq(implicit p: Parameters) extends CoreBundle()(p) {
        //     val addr = UInt(vpnBits.W)
        //     val need_gpa = Bool()
        //     val vstage1 = Bool()
        //     val stage2 = Bool()
        // }
        ptw.req.bits.bits.need_gpa := DontCare
        ptw.req.bits.bits.vstage1 := DontCare
        ptw.req.bits.bits.stage2 := DontCare

        tlb_slave_tl.d.valid := (state === s_resp)
        // tlb_slave_tl.d.bits.data := Mux(pte.leaf(), Cat(pte.ppn, req_offset), -1.S(xLen.W).asUInt)
        tlb_slave_tl.d.bits.data := Mux(pte.leaf(), Cat(pte.ppn, req_offset), req_addr)

    }
}

