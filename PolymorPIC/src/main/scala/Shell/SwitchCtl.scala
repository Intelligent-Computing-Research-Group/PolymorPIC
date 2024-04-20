package PIC

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._


// res and rep with cmd res
class SwitchInfo(sysCfg:Sys_Config) extends Bundle
{
    val op=Bool()
    val nLevels=UInt(log2Ceil(sysCfg.pic_avail_levels).W)
}

class SwitchResult(sysCfg:Sys_Config) extends Bundle
{
    val op_success=Bool()
    val avail_MatID_begin=UInt(log2Ceil(sysCfg.total_valid_mats).W)
}

import sifive.blocks.inclusivecache._
// communication with Dir
class SwitchDirQuery(params: InclusiveCacheParameters) extends Bundle
{
    val setID = UInt((params.setBits).W)
    val beginWayID = UInt((params.wayBits).W)
    val endWayID= UInt((params.wayBits).W)
}

class QueryRes(params: InclusiveCacheParameters) extends Bundle
{
    val tagID = UInt((params.tagBits).W)
}


class SwitchCtl(sysCfg:Sys_Config,params: InclusiveCacheParameters) extends Module
{
    val io=IO(new Bundle {
        val switch_req=Flipped(Decoupled(new SwitchInfo(sysCfg)))
        val switch_resp=Decoupled(new SwitchResult(sysCfg))

        // If dir can be query
        val isScheNoOtherWorks=Input(Bool())
        val picLevels=Output(UInt(log2Ceil(sysCfg.pic_avail_levels).W))
        // Query dir
        val queryDir= Decoupled(new SwitchDirQuery(params))
        val queryResFromDir=Flipped(Decoupled(new QueryRes(params)))
        val isDirQuerying=Input(Bool())

        // stall other req
        val switchIdle=Output(Bool())

        // Flush part
        val flushReq=Decoupled(new SetTagReq(params))
        val flushDone=Input(Bool())
    })

    // init
    io.switch_resp.valid:=false.B
    io.queryDir.valid:=false.B
    io.queryDir.bits:=DontCare
    io.queryResFromDir.ready:=false.B
    io.flushReq.valid:=false.B

    val switch_idle :: activate_pre_check :: activate_queryDir :: activate_dirResp :: deactivate_pre_check :: resp_op_res :: Nil = Enum(6)
    val switch_state = RegInit(switch_idle)
    io.switch_req.ready:= (switch_state===switch_idle)

    // regs
    val reqReg=Reg(new SwitchInfo(sysCfg))
    val respReg=Reg(new SwitchResult(sysCfg))
    io.switch_resp.bits:=respReg
    val runtime_PIC_Mode_levels=RegInit(0.U(log2Ceil(sysCfg.pic_avail_levels).W))
    val runtime_PIC_Mode_beginWayID=RegInit((params.cache.ways-1).U(params.wayBits.W))
    //  the allocation is from high to low
    val runtime_PIC_mode_wayID_range_high=(sysCfg.ways_per_set-1).U
    val runtime_PIC_mode_wayID_range_low=RegInit(0.U(log2Ceil(sysCfg.pic_avail_levels).W))
    val nSets=(params.cache.sets).U
    val querySetPtr=RegInit(0.U(params.setBits.W))

    val flush_queue= Module(new Queue(new SetTagReq(params),16))
    val flush_queue_full=flush_queue.io.enq.ready
    val flush_queue_not_empty=flush_queue.io.deq.valid
    flush_queue.io.enq.valid:=io.queryResFromDir.fire
    flush_queue.io.enq.bits:=DontCare

    io.switchIdle:= (switch_state===switch_idle)
    io.picLevels:=runtime_PIC_Mode_levels

    switch(switch_state)
    {
        is(switch_idle)
        {
            querySetPtr:=0.U
            when(io.switch_req.fire)
            {
                val req_info=io.switch_req.bits
                reqReg:=req_info
                switch_state:=Mux(req_info.op===PIC_Switch.ALLOC,activate_pre_check,deactivate_pre_check)
            }
        }
        is(activate_pre_check)
        {
            val total_levels=reqReg.nLevels+runtime_PIC_Mode_levels
            val valid_op= (total_levels<=sysCfg.pic_avail_levels.U)
            respReg.op_success:=Mux(valid_op,true.B,false.B)
            respReg.avail_MatID_begin:= Mux(valid_op,(sysCfg.total_valid_mats.U-total_levels*(sysCfg.nMat_per_level.U)),0.U)
            runtime_PIC_Mode_levels:=Mux(valid_op,total_levels,runtime_PIC_Mode_levels) // put after flush
            switch_state:=Mux(valid_op&&io.isScheNoOtherWorks,activate_queryDir,resp_op_res)

            // debug info
            when(valid_op)
            {
                printf("Activate picLevels=%d\n",total_levels)
            }
        }
        is(activate_queryDir)
        {
            io.queryDir.valid:=true.B    // also need schedular free and the 
            val endWayId=Mux(runtime_PIC_Mode_beginWayID===(params.cache.ways-1).U,
                                                runtime_PIC_Mode_beginWayID,runtime_PIC_Mode_beginWayID-1.U)
            when(io.queryDir.fire)
            {
                io.queryDir.bits.endWayID:=endWayId
                io.queryDir.bits.beginWayID := (endWayId-(sysCfg.nWay_per_level.U)*reqReg.nLevels)+1.U
                io.queryDir.bits.setID:=querySetPtr
                querySetPtr:=querySetPtr+1.U
                switch_state:=activate_dirResp
            }
        }
        is(activate_dirResp)    // different ways in the same set
        {
            io.queryResFromDir.ready:=flush_queue_full
            when(io.queryResFromDir.fire)
            {
                val flush_tag=io.queryResFromDir.bits.tagID
                flush_queue.io.enq.bits.set:=querySetPtr
                flush_queue.io.enq.bits.tag:=flush_tag
                switch_state:=Mux(querySetPtr===nSets,resp_op_res,activate_queryDir)
            }
            .otherwise
            {switch_state:=Mux(io.isDirQuerying,switch_state,Mux(querySetPtr===(nSets-1.U),resp_op_res,activate_queryDir))}
        }
        is(deactivate_pre_check)
        {
            val levels_to_deactivate=reqReg.nLevels
            val valid_op= (runtime_PIC_Mode_levels>=levels_to_deactivate)
            val left_levels=runtime_PIC_Mode_levels-levels_to_deactivate
            respReg.op_success:=Mux(valid_op,true.B,false.B)
            respReg.avail_MatID_begin:= Mux(valid_op,((sysCfg.total_levels.U-left_levels)*(sysCfg.nMat_per_level.U)-1.U),0.U)
            runtime_PIC_Mode_levels:=Mux(valid_op,left_levels,runtime_PIC_Mode_levels)
            switch_state:=Mux(valid_op&&io.isScheNoOtherWorks,resp_op_res,resp_op_res)
        }
        is(resp_op_res)
        {
            io.switch_resp.valid:=(!flush_queue_not_empty)
            when(io.switch_resp.fire)
            {
                switch_state:=switch_idle
            }
        }
    }

    // val deq_idle :: activate_pre_check  :: deactivate_pre_check::resp_op_res::Nil = Enum(4)
    // val deq_state = RegInit(switch_idle)


    val flush_idle :: send_flush_req::wait_flushDone::Nil = Enum(3)
    val flush_state = RegInit(switch_idle)

    flush_queue.io.deq.ready:=false.B
    io.flushReq.bits:=flush_queue.io.deq.bits

    val flushReqReg=Reg(new SetTagReq(params))

    switch(flush_state)
    {
        is(flush_idle)
        {
            flush_queue.io.deq.ready:=true.B
            when(flush_queue.io.deq.fire)
            {
                flushReqReg:=flush_queue.io.deq.bits
                flush_state:=send_flush_req
            }
        }
        is(send_flush_req)
        {
             io.flushReq.valid:=true.B
             when(io.flushReq.fire)
             {
                io.flushReq.bits:=flushReqReg
                flush_state:=wait_flushDone
             }
        }
        is(wait_flushDone)
        {
            flush_state:=Mux(io.flushDone,flush_idle,flush_state)
        }
    }
}

