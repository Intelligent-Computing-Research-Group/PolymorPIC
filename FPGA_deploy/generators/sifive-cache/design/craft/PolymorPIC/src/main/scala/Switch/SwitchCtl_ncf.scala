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


class SwitchCtl_ncf(sysCfg:Sys_Config,params: InclusiveCacheParameters) 
extends SwitchCtl(sysCfg,params)
{
    // init
    io.switch_resp.valid:=false.B
    io.queryDir.valid:=false.B
    io.queryDir.bits:=DontCare
    io.queryResFromDir.ready:=false.B
    io.flushReq.valid:=false.B

    val switch_idle :: activate_pre_check ::  enq_flush_queue :: check_finish :: deactivate_pre_check :: resp_op_res :: Nil = Enum(6)
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

    io.switchIdle:= (switch_state===switch_idle)
    io.picLevels:=runtime_PIC_Mode_levels

    val nblock_per_group_to_flush = reqReg.nLevels<<sysCfg.ddr_nBlock_ls_query_per_group

    val addr_group_base_ptr=RegInit(0.U(params.inner.bundle.addressBits.W))
    val addr_offset=RegInit(0.U(params.inner.bundle.addressBits.W))
    val (tag, set, offset) = params.parseAddress(addr_group_base_ptr+addr_offset)
    flush_queue.io.enq.valid:=(switch_state===enq_flush_queue)
    flush_queue.io.enq.bits.set:=set
    flush_queue.io.enq.bits.tag:=tag

    val group_ptr=RegInit(0.U(log2Ceil(sysCfg.ddr_nGroup+1).W))
    val block_ptr=RegInit(0.U(log2Ceil(params.cache.sets+1).W))

    switch(switch_state)
    {
        is(switch_idle)
        {
            querySetPtr:=0.U
            when(io.switch_req.fire)
            {
                val req_info=io.switch_req.bits
                reqReg:=req_info
                addr_group_base_ptr:=0.U
                addr_offset:=0.U
                group_ptr:=0.U
                block_ptr:=0.U
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
                                Mux(io.isScheNoOtherWorks,enq_flush_queue,switch_state),
                                resp_op_res)

            // debug info
            when(valid_op)
            {
                printf("Activate picLevels=%d\n",total_levels)
            }
        }
        // Cal begin addr
        // for group 0-> sysCfg.ddr_nGroup
        //         for block 0-> nblock_per_group_to_flush
        //             send the addr's tag set to queue
        // reg:
        // addr_ptr;group_ptr;level_ptr;block_ptr;
        is(enq_flush_queue)
        {
            when(flush_queue.io.enq.fire)
            {
                switch_state:=check_finish
            }
        }
        is(check_finish)
        {
            val isLastGroup = (group_ptr===(sysCfg.ddr_nGroup-1).U) 
            val isLastBlock = (block_ptr===(nblock_per_group_to_flush-1.U))

            val should_finish=(isLastGroup&isLastBlock)

            when(should_finish)                 // All finish
            {
                runtime_cache_Mode_endWayID:=runtime_cache_Mode_endWayID-(sysCfg.nWay_per_level.U)*reqReg.nLevels
                switch_state:=resp_op_res
            }
            .otherwise  // else
            {
                group_ptr:=Mux(isLastBlock,group_ptr+1.U,group_ptr)
                block_ptr:=Mux(isLastBlock,0.U,block_ptr+1.U)
                addr_group_base_ptr:=Mux(isLastBlock,addr_group_base_ptr+sysCfg.ddr_group_bytes.U,addr_group_base_ptr)
                addr_offset:=Mux(isLastBlock,0.U,addr_offset+params.cache.blockBytes.U)

                switch_state:=enq_flush_queue
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
                                        (sysCfg.totalMatNum-1).U,MatID_begin)   // make a c    heck here because total release causes overflow, and free must be non-zero.
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

