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
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._

class Accumu_req(sysCfg:Sys_Config) extends Bundle 
{
    val src_arrayNum=UInt(log2Ceil(sysCfg.numAccRes+1).W)
    val base_src_picAddr=UInt((sysCfg.accessCacheFullAddrLen).W)
    val dest_picAddr=UInt((sysCfg.accessCacheFullAddrLen).W)
    val row_num=UInt(sysCfg.inferWidth(sysCfg.acc_src_maxRowNum).W)
    val bitWidth=Bool()
}

class Accumulator(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{
    val core_configs=sysCfg.core_config
    val arrayAddrLen=log2Ceil(sysCfg.numArraysTotal)+log2Ceil(core_configs.arrayNums*core_configs.wordlineNums)
    val io = IO(new Bundle {
        // request from rocc
        val accumulate_req = Flipped(Decoupled(new Accumu_req(sysCfg)))
        val busy = Output(Bool())

        // connect to bank
        val accessArrayReq=Decoupled(new ReqPackage(sysCfg))
        val dataReadFromBank=Input(UInt(64.W))
        val dataReadValid=Input(Bool())
    })

    val arrayNum_reg=RegInit(0.U(log2Ceil(sysCfg.numAccRes+1).W))
    val base_src_picAddr_reg=RegInit((0.U((sysCfg.accessCacheFullAddrLen).W)))
    val dest_picAddr_reg=RegInit((0.U(log2Ceil(core_configs.total_arrays*core_configs.wordlineNums).W)))
    val accumulator_buf=RegInit(0.U(64.W))
    val total_rowNum=RegInit(0.U(sysCfg.inferWidth(sysCfg.acc_src_maxRowNum).W))
    val row_ptr=RegInit(0.U(sysCfg.inferWidth(sysCfg.acc_src_maxRowNum).W))
    val read_op_array_ptr=RegInit(0.U(log2Ceil(sysCfg.numAccRes+1).W)) // read_op_array_ptr = 0 1 2 .. total-1
    val finishRead = (read_op_array_ptr===arrayNum_reg)
    val receive_op_array_ptr=RegInit(0.U(log2Ceil(sysCfg.numAccRes+1).W)) // read_op_array_ptr = 0 1 2 .. total-1
    val finishReceived = (receive_op_array_ptr===arrayNum_reg)
    val addr_offset=read_op_array_ptr<<log2Ceil(core_configs.wordlineNums*4)
    val selected_array_row_addr=base_src_picAddr_reg+row_ptr+addr_offset
    val bitWidth_reg=RegInit(CalInfo.ACC_32BIT)

    val proc_idle :: proc_read :: proc_write_back ::Nil = Enum(3)
    val proc_state = RegInit(proc_idle)
    io.accumulate_req.ready:= (proc_state===proc_idle)
    io.accessArrayReq.valid:= false.B
    io.accessArrayReq.bits.dataWrittenToBank:= 0.U
    io.accessArrayReq.bits.optype:= AccessArrayType.READ
    io.accessArrayReq.bits.addr:= 0.U
    val busy= (proc_state=/=proc_idle)
    io.busy:= busy

    
    switch(proc_state)
    {
        is(proc_idle)
        {   
            when(io.accumulate_req.fire)
            {
                arrayNum_reg:=io.accumulate_req.bits.src_arrayNum
                base_src_picAddr_reg:=io.accumulate_req.bits.base_src_picAddr
                dest_picAddr_reg:=io.accumulate_req.bits.dest_picAddr
                total_rowNum:=io.accumulate_req.bits.row_num
                accumulator_buf:=0.U
                row_ptr:=0.U
                read_op_array_ptr:=0.U
                receive_op_array_ptr:=0.U
                bitWidth_reg:=io.accumulate_req.bits.bitWidth

                proc_state :=proc_read
            }

        }
        is(proc_read)
        {   
            io.accessArrayReq.valid:= !finishRead
            // Acc_req_sent should next cycle receive data
            when(io.accessArrayReq.fire)
            {
                io.accessArrayReq.bits.optype:=AccessArrayType.READ
                io.accessArrayReq.bits.addr:=selected_array_row_addr
                // Point to the next array, read the next operand to be added (64b)
                read_op_array_ptr:=read_op_array_ptr+1.U
            }

            // val is_read_op=RegNext(io.accessArrayReq.bits.optype)===AccessArrayType.READ
            when(io.dataReadValid)
            {
                accumulator_buf := Mux(bitWidth_reg===CalInfo.ACC_32BIT,
                                        add_32b(accumulator_buf,io.dataReadFromBank),
                                        add_16b(accumulator_buf,io.dataReadFromBank))
                receive_op_array_ptr := receive_op_array_ptr+1.U
            }

            when(finishRead&finishReceived)
            {
                read_op_array_ptr := 0.U
                receive_op_array_ptr:=0.U
                proc_state := proc_write_back
            }
        }
        is(proc_write_back)
        {
            io.accessArrayReq.valid:= true.B
            when(io.accessArrayReq.fire)
            {
                io.accessArrayReq.bits.optype:=AccessArrayType.WRITE
                io.accessArrayReq.bits.addr:=dest_picAddr_reg+row_ptr
                io.accessArrayReq.bits.dataWrittenToBank:=accumulator_buf

                accumulator_buf:=0.U
                row_ptr:=row_ptr+1.U

                proc_state:=Mux(row_ptr===(total_rowNum-1.U),
                            proc_idle,
                            proc_read)
                
            }
        }
    }


    def add_32b(acc_reg:UInt,acc_data:UInt): UInt=
    {
        val lsb=acc_reg(31,0)+acc_data(31,0)
        val hsb=acc_reg(63,32)+acc_data(63,32)

        Cat(hsb,lsb)

    }

    def add_16b(acc_reg:UInt,acc_data:UInt): UInt=
    {
        val lsb0=acc_reg(15,0)+acc_data(15,0)
        val lsb1=acc_reg(31,16)+acc_data(31,16)
        val hsb0=acc_reg(47,32)+acc_data(47,32)
        val hsb1=acc_reg(63,48)+acc_data(63,48)

        Cat(hsb1,hsb0,lsb1,lsb0)
    }

}