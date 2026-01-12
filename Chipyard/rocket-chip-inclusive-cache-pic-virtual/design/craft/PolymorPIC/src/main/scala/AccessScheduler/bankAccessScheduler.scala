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

import chisel3._ // VecInit

import chisel3.util._ // MuxCase
import freechips.rocketchip.tile._
import freechips.rocketchip.subsystem._  // for getting subsystembus parameter
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._ // LazyModule
import freechips.rocketchip.rocket._ // cache interface
import freechips.rocketchip.util._

// Request the BankArray read or write
class ReqPackage(sysCfg:Sys_Config) extends Bundle {
    val addr = UInt((sysCfg.accessCacheFullAddrLen).W)
    val optype = Bool()
    val dataWrittenToBank= UInt(64.W) 
}


class AccessBank_Arb(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit val p: Parameters) extends Module
{
    val mat_inner_offset=log2Ceil(core_configs.arrayNums*core_configs.wordlineNums)
    val client_num=sysCfg.numAccessCacheClient
    val io=IO(new Bundle {
        // vec size=2 one for p2s, one for L auto load , one for accumulate
        val request=Vec(client_num, Flipped(Decoupled(new ReqPackage(sysCfg))))
        val dataReadFromBank=Output(UInt(64.W)) // This is returned to clients
        val dataReadValid=Vec(client_num,Output(Bool()))

        // Connected to banks, used to access mem directly
        val cache_enable = Output(Bool()) // Select subarray
        val cache_write = Output(Bool())
        val cache_addr = Output(UInt((log2Ceil(sysCfg.numBanks)+log2Ceil(sysCfg.matPerBank)+mat_inner_offset).W))
        val cache_data_to_bank = Output(UInt(core_configs.bitlineNums.W))
        val cache_data_from_bank = Input(UInt(core_configs.bitlineNums.W))  // This is returned from banks
        
        // Whether the selected bank is ready (not being accessed by cache)
        val mem_ready=Vec(sysCfg.numBanks,Input(Bool()))
        
    })
    dontTouch(io.mem_ready)

    val access :: block ::Nil = Enum(2)
    val state=RegInit(access)

    // init
    io.cache_enable:=false.B
    io.cache_write:=false.B
    io.cache_addr:=0.U
    io.cache_data_to_bank:=0.U

    io.dataReadFromBank:=io.cache_data_from_bank

    val arbiter = Module(new RRArbiter(new ReqPackage(sysCfg), client_num))
    val chosenID=RegInit(0.U(log2Ceil(client_num).W))
    val req_reg=Reg(new ReqPackage(sysCfg))

    for(i<-0 until client_num)
    {   arbiter.io.in(i)<>io.request(i) }

    arbiter.io.out.ready:=(state===access)

    io.dataReadValid := VecInit(Seq.fill(client_num)(false.B))
    
    val if_block=RegInit(false.B)
    io.cache_addr:=Mux(if_block,req_reg.addr,arbiter.io.out.bits.addr)
    io.cache_data_to_bank:=Mux(if_block,req_reg.dataWrittenToBank,arbiter.io.out.bits.dataWrittenToBank)

    val selected_bankID=Mux(if_block,
                            sysCfg.get_bankID(req_reg.addr),
                            sysCfg.get_bankID(arbiter.io.out.bits.addr))
    val selected_bank_ready_state=MuxCase(false.B,(0 until sysCfg.numBanks).map { bankID =>
        (selected_bankID===bankID.U) -> io.mem_ready(bankID)
    })

    val readHappen=WireInit(false.B)

    for(i<-0 until client_num)
    {
        val chosen = chosenID===i.U
        io.dataReadValid(i):= chosen&RegNext(readHappen)
    }

    switch(state)
    {
        is(access)
        {
            when(arbiter.io.out.fire)
            {
                req_reg:=arbiter.io.out.bits
                val optype=arbiter.io.out.bits.optype
                val isWrite= (optype===AccessArrayType.WRITE)
                val canAccess= (selected_bank_ready_state===true.B)
                chosenID:=arbiter.io.chosen
                
                io.cache_enable:=canAccess
                io.cache_write:= isWrite
                if_block:= !canAccess
                readHappen:= canAccess&(!isWrite)
                state:=Mux(canAccess,access,block)
            }
        }
        is(block)
        {
            when(selected_bank_ready_state)
            {
                val optype=req_reg.optype
                val isWrite= (optype===AccessArrayType.WRITE)
                io.cache_enable:=true.B
                io.cache_write:= isWrite
                state:=access
                if_block:=false.B
                readHappen:= (!isWrite)
            }
        }
    }

}