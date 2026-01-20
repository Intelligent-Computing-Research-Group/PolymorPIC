// Copyright (c) 2025 ICRG@Shanghai Jiao Tong University
// PolymorPIC is licensed under Mulan PSL v2.
// You can use this software according to the terms and conditions of the Mulan PSL v2.
// You may obtain a copy of Mulan PSL v2 at:
//          http://license.coscl.org.cn/MulanPSL2
// THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
// EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
// MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
// See the Mulan PSL v2 for more details.

package PIC

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._

import sifive.blocks.inclusivecache._


class SwitchCtl_pic(sysCfg:Sys_Config,params: InclusiveCacheParameters)
extends SwitchCtl(sysCfg,params)
{

    println("ddr_nBlock_ls_query_per_group",sysCfg.ddr_nBlock_ls_query_per_group)
    println("ddr_group_bytes",sysCfg.ddr_group_bytes)
    println("ddr_nGroup",sysCfg.ddr_nGroup)

    // init
    io.switch_resp.valid:=false.B
    io.queryDir.valid:=false.B
    io.queryDir.bits:=DontCare
    io.queryResFromDir.ready:=false.B
    io.flushReq.valid:=false.B

    val switch_idle :: activate_pre_check :: activate_queryDir :: activate_dirResp :: check_finish :: deactivate_pre_check :: resp_op_res :: Nil = Enum(7)
    val switch_state = RegInit(switch_idle)
    io.switch_req.ready:= (switch_state===switch_idle)

    val flush_idle :: send_flush_req::wait_flushDone::Nil = Enum(3)
    val flush_state = RegInit(switch_idle)

    // regs
    val reqReg=Reg(new SwitchInfo(sysCfg))
    val respReg=Reg(new SwitchResult(sysCfg))
    io.switch_resp.bits:=respReg
    val runtime_PIC_Mode_levels=RegInit(0.U(log2Ceil(sysCfg.pic_avail_levels).W))
    val runtime_PIC_Mode_levels_for_assert=RegInit(0.U(log2Ceil(sysCfg.pic_avail_levels).W))
    val runtime_cache_Mode_endWayID=RegInit((params.cache.ways-1).U(params.wayBits.W))
    io.picActivated:=(runtime_PIC_Mode_levels_for_assert>0.U)
    io.cacheLevelEnd:=(sysCfg.total_levels-1).U-runtime_PIC_Mode_levels_for_assert
    val nSets=(params.cache.sets).U
    val querySetPtr=RegInit(0.U(params.setBits.W))

    val flush_queue= Module(new Queue(new SetTagReq(params),16))
    val flush_queue_not_full=flush_queue.io.enq.ready
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
            assert(reqReg.nLevels>0.U,"0 not allowed")
            val total_levels=reqReg.nLevels+&runtime_PIC_Mode_levels
            val valid_op= (total_levels<=sysCfg.pic_avail_levels.U)
            respReg.op_success:=Mux(valid_op,true.B,false.B)
            respReg.avail_MatID_begin:= Mux(valid_op,(sysCfg.totalMatNum.U-total_levels*(sysCfg.nMat_per_level.U)),respReg.avail_MatID_begin)
            runtime_PIC_Mode_levels:=Mux(valid_op&&io.isScheNoOtherWorks,total_levels,runtime_PIC_Mode_levels) // put after flush
            switch_state:=Mux(valid_op,
                                Mux(io.isScheNoOtherWorks,activate_queryDir,switch_state),
                                resp_op_res)

            // debug info
            when(valid_op)
            {
                printf("Activate picLevels=%d\n",total_levels)
            }
        }
        is(activate_queryDir)
        {
            io.queryDir.valid:=true.B    // also need schedular free and the 
            when(io.queryDir.fire)
            {
                io.queryDir.bits.endWayID:=runtime_cache_Mode_endWayID
                io.queryDir.bits.beginWayID := (runtime_cache_Mode_endWayID-(sysCfg.nWay_per_level.U)*reqReg.nLevels)+1.U
                io.queryDir.bits.setID:=querySetPtr
                switch_state:=activate_dirResp
            }
        }
        is(activate_dirResp)    // different ways in the same set
        {
            io.queryResFromDir.ready:=flush_queue_not_full
            when(io.queryResFromDir.fire)
            {
                val flush_tag=io.queryResFromDir.bits.tagID
                flush_queue.io.enq.bits.set:=querySetPtr
                flush_queue.io.enq.bits.tag:=flush_tag
                switch_state:=check_finish
            }
            .otherwise
            {switch_state:=Mux(io.isDirQuerying,switch_state,check_finish)}
        }
        is(check_finish)
        {
            val iLastSet= (querySetPtr===(params.cache.sets-1).U)
            val iLastWay= (io.isDirQuerying===false.B)
            val should_finish=(iLastSet&iLastWay)
            val stil_has_ways_in_the_set= !iLastWay
            when(should_finish)                 // 全部结束
            {
                runtime_cache_Mode_endWayID:=runtime_cache_Mode_endWayID-(sysCfg.nWay_per_level.U)*reqReg.nLevels
                switch_state:=resp_op_res
            }
            .elsewhen(stil_has_ways_in_the_set) // 该set中还有way没被查
            {
                switch_state:=activate_dirResp
            }
            .otherwise  // 当前set结束，但是还有set
            {
                querySetPtr:=querySetPtr+1.U
                switch_state:=activate_queryDir
            }
        }
        is(deactivate_pre_check)
        {
            val levels_to_deactivate=reqReg.nLevels
            val valid_op= (runtime_PIC_Mode_levels>=levels_to_deactivate)
            val left_levels=runtime_PIC_Mode_levels-levels_to_deactivate
            respReg.op_success:=Mux(valid_op,true.B,false.B)
            val MatID_begin=(sysCfg.total_levels.U-left_levels)*(sysCfg.nMat_per_level.U)
            val avail_MatID_begin=Mux(MatID_begin===sysCfg.totalMatNum.U,
                                        (sysCfg.totalMatNum-1).U,MatID_begin)   // 若全部释放，结果会溢出，但是free结果不可能是0，所以这里进行判断
            respReg.avail_MatID_begin:= Mux(valid_op,avail_MatID_begin,respReg.avail_MatID_begin)
            runtime_PIC_Mode_levels:=Mux(valid_op,left_levels,runtime_PIC_Mode_levels)
            runtime_cache_Mode_endWayID:=Mux(valid_op,runtime_cache_Mode_endWayID+(sysCfg.nWay_per_level.U)*reqReg.nLevels,runtime_cache_Mode_endWayID)
            switch_state:=resp_op_res
        }
        is(resp_op_res)
        {
            io.switch_resp.valid:=(!flush_queue_not_empty)&(flush_state===flush_idle)
            when(io.switch_resp.fire)
            {
                runtime_PIC_Mode_levels_for_assert:=runtime_PIC_Mode_levels
                switch_state:=switch_idle
            }
        }
    }

    // val deq_idle :: activate_pre_check  :: deactivate_pre_check::resp_op_res::Nil = Enum(4)
    // val deq_state = RegInit(switch_idle)


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

