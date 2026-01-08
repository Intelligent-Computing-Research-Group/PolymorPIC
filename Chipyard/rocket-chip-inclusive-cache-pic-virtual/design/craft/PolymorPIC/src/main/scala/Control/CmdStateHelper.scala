// Copyright (c) [Year] [name of copyright holder]
// [Software Name] is licensed under Mulan PSL v2.
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

object QryTabClient
{
    val MAIN_SETTER=0
    val READER=1
    val LD_L=2
    val LD_R=3
    val EXE=4
    val LD_P=5
    val ACC=6
    val ST_P=7
    val total_client=8
}

class CmdTableEntry extends Bundle
{
    // Not in queue
    val VALID=Bool()
    // is finished?
    val FINISH=Bool()
}

class QueryInfo(sysCfg:Sys_Config) extends Bundle
{
    val cmdID=UInt(sysCfg.cmdID_sigLen.W)
    val is_finish=Bool()  // If false, it is init.
}

class CmdStateHelper(sysCfg:Sys_Config,coreCfg:PolymorPIC_Kernal_Config) extends Module
{
    val client_num= QryTabClient.total_client
    val io = IO(new Bundle {
        val query_req=Vec(client_num, Flipped(Decoupled(new QueryInfo(sysCfg))))
        val is_finish=Output(Bool())
    })

    io.is_finish:=false.B

    val arbiter = Module(new RRArbiter(new QueryInfo(sysCfg), client_num))
    for(i<-0 until client_num)
    {
        arbiter.io.in(i)<>io.query_req(i)
    }

    val proc_idle :: init_state :: set_finish :: check_if_finish :: set_invalid :: Nil = Enum(5)
    val proc_state = RegInit(proc_idle)
    arbiter.io.out.ready:=(proc_state===proc_idle)

    // memory read and write logic >>>>>>>>>>>
    val cmd_state_table = SyncReadMem(sysCfg.cmdID_range,new CmdTableEntry)
    val writeEn_wire=WireInit(false.B)
    val en_wire=WireInit(false.B)
    val addr_wire=WireInit(0.U(sysCfg.cmdID_sigLen.W))
    val data_wire=WireInit(0.U.asTypeOf(new CmdTableEntry))
    when(writeEn_wire & en_wire){
        cmd_state_table.write(addr_wire,data_wire)
    }
    
    val table_read_out_wire=cmd_state_table.read(addr_wire, en_wire&&(!writeEn_wire))
    // <<<<<<<<<<<<<<<< memory read and write logic

    val req_cmdID_reg=RegInit(0.U(sysCfg.cmdID_sigLen.W))

    // Main logic
    switch(proc_state)
    {
        is(proc_idle)
        {
            when(arbiter.io.out.fire)
            {
                val chosen_id=arbiter.io.chosen
                val req_info=arbiter.io.out.bits
                req_cmdID_reg:=req_info.cmdID
                proc_state:=Mux(chosen_id===QryTabClient.READER.U,
                                check_if_finish,
                                Mux(req_info.is_finish,set_finish,init_state)
                                )
                en_wire:=true.B
                addr_wire:=req_info.cmdID
            }
        }
        is(init_state)
        {
            writeEn_wire:=true.B
            en_wire:=true.B
            addr_wire:=req_cmdID_reg
            // assert(table_read_out_wire.VALID === false.B,"The cmdID is inited!")
            data_wire.VALID  := true.B
            data_wire.FINISH  := false.B

            proc_state:=proc_idle
        }
        is(set_finish)
        {
            writeEn_wire:=true.B
            en_wire:=true.B
            addr_wire:=req_cmdID_reg
            assert(table_read_out_wire.VALID === true.B,"The cmdID is not inited before! You cant visit it!")
            data_wire.FINISH  := true.B
            data_wire.VALID  := true.B

            proc_state:=proc_idle
        }
        is(check_if_finish)
        {
            val is_finish=table_read_out_wire.FINISH
            io.is_finish:=is_finish
            assert(table_read_out_wire.VALID === true.B,"The cmdID is not inited before! You cant visit it!")

            // If finish, invalid the cmd record!
            proc_state:=Mux(is_finish,set_invalid,proc_idle)
        }
        is(set_invalid)
        {
            writeEn_wire:=true.B
            en_wire:=true.B
            addr_wire:=req_cmdID_reg
            data_wire.VALID  := false.B

            assert(table_read_out_wire.VALID === true.B,"The cmdID is not inited before! You cant edit it!")

            // If finish, invalid the cmd record!
            proc_state:=proc_idle
        }
    }
}