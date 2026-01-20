package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._


class FlushReqRouter(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    // Reveive request from switch or sinkX
    val sinkXreq = Flipped(Decoupled(new SetTagReq(params)))
    val switchCtlReq = Flipped(Decoupled(new SetTagReq(params)))

    // Send flush request to cache schedular
    val flushReqToSche= Decoupled(new FullRequest(params))

    // ----------------------------------------------------

    // receive resp from sourceX(flush finish)
    val respFromSourceX=Flipped(Decoupled(new SourceXRequest(params)))

    // Path one (ori): transfer resp to schedular and finally to cbus
    val sourceX_finish_resp=Decoupled(new SourceXRequest(params))
    // Path two (switchCtl): transfer resp to switchCtl
    val switchFlushOK=Output(Bool())
  })
    io.flushReqToSche.valid:=false.B
    io.flushReqToSche.bits:=DontCare
    io.respFromSourceX.ready:=false.B
    io.switchFlushOK:=false.B
    io.sourceX_finish_resp.valid:=false.B
    io.sourceX_finish_resp.bits:=DontCare

    val clientN=2   // one is SwitchCtl another is original flush
    val arbiter = Module(new RRArbiter(new SetTagReq(params), clientN))
    arbiter.io.in(0)<>io.sinkXreq
    arbiter.io.in(1)<>io.switchCtlReq

    arbiter.io.out.ready:=false.B

    val deal_idle ::  is_sinkXreq :: send_flush_req::is_switchCtlReq :: Nil = Enum(4)
    val deal_state = RegInit(deal_idle)

    val set_tag_Reg=Reg(new SetTagReq(params))
    val chosenID=RegInit(0.U(2.W))

    io.flushReqToSche.bits.prio   := VecInit(1.U(3.W).asBools) // same prio as A
    io.flushReqToSche.bits.control:= true.B
    io.flushReqToSche.bits.opcode := 0.U
    io.flushReqToSche.bits.param  := 0.U
    io.flushReqToSche.bits.size   := params.offsetBits.U
    // The source does not matter, because a flush command never allocates a way.
    // However, it must be a legal source, otherwise assertions might spuriously fire.
    io.flushReqToSche.bits.source := params.inner.client.clients.map(_.sourceId.start).min.U
    io.flushReqToSche.bits.offset := 0.U
    io.flushReqToSche.bits.put    := 0.U
    io.flushReqToSche.bits.set    := set_tag_Reg.set
    io.flushReqToSche.bits.tag    := set_tag_Reg.tag

    switch(deal_state)
    {
        is(deal_idle)
        {
            arbiter.io.out.ready:=true.B
            when(arbiter.io.out.fire)
            {
                set_tag_Reg:=arbiter.io.out.bits
                chosenID:=arbiter.io.chosen
                deal_state:=send_flush_req
            }
        }
        is(send_flush_req)
        {
          io.flushReqToSche.valid:=true.B
          when(io.flushReqToSche.fire)
          { deal_state:=Mux(chosenID===0.U,is_sinkXreq,is_switchCtlReq) }
        }
        is(is_sinkXreq)
        {
            io.respFromSourceX<>io.sourceX_finish_resp
            when(io.respFromSourceX.fire){deal_state:=deal_idle}
        }
        is(is_switchCtlReq)
        {
            io.respFromSourceX.ready:=true.B
            when(io.respFromSourceX.fire)
            {
                io.switchFlushOK:=true.B
                deal_state:=deal_idle
            }
        }
    }
}
