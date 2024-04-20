package PIC

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._


// IMPORTANT
// THe module is used for sim switch function when in sbus side
// THe module is used for sim switch function when in sbus side
// THe module is used for sim switch function when in sbus side
// THe module is used for sim switch function when in sbus side


class SimSwitchCtl(sysCfg:Sys_Config) extends Module
{
    val io=IO(new Bundle {
        val switch_req=Flipped(Decoupled(new SwitchInfo(sysCfg)))
        val switch_resp=Decoupled(new SwitchResult(sysCfg))
    })

    // init
    io.switch_resp.valid:=false.B

    val switch_idle :: activate_pre_check  :: deactivate_pre_check::resp_op_res::Nil = Enum(4)
    val switch_state = RegInit(switch_idle)
    io.switch_req.ready:= (switch_state===switch_idle)

    // regs
    val reqReg=Reg(new SwitchInfo(sysCfg))
    val respReg=Reg(new SwitchResult(sysCfg))
    io.switch_resp.bits:=respReg
    val runtime_PIC_Mode_levels=RegInit(0.U(log2Ceil(sysCfg.pic_avail_levels).W))
    //  the allocation is from high to low
    val runtime_PIC_mode_wayID_range_high=(sysCfg.ways_per_set-1).U
    val runtime_PIC_mode_wayID_range_low=RegInit(0.U(log2Ceil(sysCfg.pic_avail_levels).W))


    switch(switch_state)
    {
        is(switch_idle)
        {
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
            // respReg.avail_MatID_begin:= Mux(valid_op,(sysCfg.total_valid_mats.U-total_levels*(sysCfg.nMat_per_level.U)),0.U)
            // runtime_PIC_Mode_levels:=Mux(valid_op,total_levels,runtime_PIC_Mode_levels) // put after flush
            
            switch_state:=resp_op_res
        }
        is(deactivate_pre_check)
        {
            val levels_to_deactivate=reqReg.nLevels
            val valid_op= (runtime_PIC_Mode_levels>=levels_to_deactivate)
            val left_levels=runtime_PIC_Mode_levels-levels_to_deactivate
            respReg.op_success:=Mux(valid_op,true.B,false.B)
            respReg.avail_MatID_begin:= Mux(valid_op,(left_levels*(sysCfg.nMat_per_level.U)-1.U),0.U)
            runtime_PIC_Mode_levels:=Mux(valid_op,left_levels,runtime_PIC_Mode_levels)
            switch_state:=Mux(valid_op,resp_op_res,resp_op_res)
        }
        is(resp_op_res)
        {
            io.switch_resp.valid:=true.B
            when(io.switch_resp.fire)
            {
                switch_state:=switch_idle
            }
        }
    }


}