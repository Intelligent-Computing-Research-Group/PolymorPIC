// Copyright (c) 2025 ICRG@Shanghai Jiao Tong University
// PolymorPIC is licensed under Mulan PSL v2.
// You can use this software according to the terms and conditions of the Mulan PSL v2.
// You may obtain a copy of Mulan PSL v2 at:
//          http://license.coscl.org.cn/MulanPSL2
// THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
// EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
// MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
// See the Mulan PSL v2 for more details.
/**
  * Memories are given special treatment in Chisel since hardware implementations 
  of memory vary greatly. For example, FPGA memories are instantiated quite differently 
  from ASIC memories. Chisel defines a memory abstraction that can map to either simple 
  Verilog behavioural descriptions or to instances of memory modules that are available 
  from external memory generators provided by foundry or IP vendors.
  */
package PIC

import chisel3._
import chisel3.util._
import chisel3.{Data, Vec}
import chisel3.util.log2Ceil
import freechips.rocketchip.util._

class QueryMatReq(sysCfg:Sys_Config) extends Bundle
{
  val matID=UInt(log2Ceil(sysCfg.totalMatNum).W)
}

class BanksShell(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config) extends Module 
{
    val mat_num_per_bank=sysCfg.matPerBank
    val mat_inner_offset=log2Ceil(core_configs.arrayNums*core_configs.wordlineNums)
    val full_addrLen=log2Ceil(sysCfg.numBanks)+log2Ceil(mat_num_per_bank)+mat_inner_offset
    val io = IO(new Bundle {
        // Cache IO
        val cache_enable = Input(Bool()) // Choose subarray
        val cache_write = Input(Bool())
        val cache_addr = Input(UInt(full_addrLen.W))
        val cache_dataIn = Input(UInt(core_configs.bitlineNums.W))
        val cache_dataOut = Output(UInt(core_configs.bitlineNums.W))

        // Set up signals
        val set_up_addr=Input(UInt(log2Ceil(sysCfg.totalMatNum).W))
        val set_up_io=Input(new SetUpIO(sysCfg.core_config))

        // Load L
        val request_vec=Vec(sysCfg.totalMatNum, Decoupled(new RequestAddress(core_configs)))
        val response_vec=Vec(sysCfg.totalMatNum, Flipped(Decoupled(Bool())))
        val load_vec_en =Input(Bool())
        val load_vec_addr=Input(UInt(log2Ceil(sysCfg.totalMatNum).W))
        val vec_data=Input(UInt((core_configs.bitlineNums).W))

        // Busy state of each mat
        val query_mat_req=Vec(sysCfg.query_clients,Flipped(Decoupled(new QueryMatReq(sysCfg))))
        val mat_busy=Output(Bool())
    })

    val banks_list=Seq.tabulate(sysCfg.numBanks) {
        i =>
        DescribedPolymorPIC(
            name = s"cc_banks_$i",
            desc = "Banked Store",
            size = sysCfg.sizeBank_64b,
            data = UInt(64.W),
            sysCfg = sysCfg,
        )
    }

    // Data IO
    val access_bank_ID=sysCfg.get_bankID(io.cache_addr)
    val cache_addr_bank_inner=sysCfg.get_fulladdr_sent_to_bank(io.cache_addr)
    banks_list.zipWithIndex.map{case(bank,index)=>
        bank.io.cache_enable :=  Mux(index.U===access_bank_ID && io.cache_enable,true.B,false.B)
        bank.io.cache_write  :=  Mux(index.U===access_bank_ID && io.cache_write,true.B,false.B)
        bank.io.cache_addr   :=  cache_addr_bank_inner
        bank.io.cache_dataIn :=  io.cache_dataIn
    }
    io.cache_dataOut:=MuxLookup(RegEnable(access_bank_ID,!io.cache_write && io.cache_enable), 0.U, banks_list.zipWithIndex.map { case (bank, index) =>
        (index.U -> bank.io.cache_dataOut)
    })

    // setup
    val set_up_selected_bank=sysCfg.get_bankID(io.set_up_addr)
    val set_up_mat_offset=sysCfg.get_in_bank_matID(io.set_up_addr)
    banks_list.zipWithIndex.map{case(bank,index)=>
        val set_up_io               =  bank.io.set_up_io
        bank.io.set_up_addr        :=  set_up_mat_offset
        set_up_io.exec             :=  Mux(index.U===set_up_selected_bank && io.set_up_io.exec,true.B,false.B)
        set_up_io.accWidth         :=  io.set_up_io.accWidth
        set_up_io._L_block_row     :=  io.set_up_io._L_block_row
        set_up_io._L_precision     :=  io.set_up_io._L_precision
        set_up_io._L_vec_fetch_addr:=  io.set_up_io._L_vec_fetch_addr
        set_up_io.signed_L        :=  io.set_up_io.signed_L
        set_up_io.signed_R_last_exist        :=  io.set_up_io.signed_R_last_exist
        set_up_io._R_base_bit       :=  io.set_up_io._R_base_bit
        set_up_io._R_block_row     :=  io.set_up_io._R_block_row
        set_up_io.nBuf              :=  io.set_up_io.nBuf
        set_up_io.nCal              :=  io.set_up_io.nCal
    }

    // Load L
    val load_vec_bank=sysCfg.get_bankID(io.load_vec_addr)
    val load_vec_in_bank_matID=sysCfg.get_in_bank_matID(io.load_vec_addr)
    banks_list.zipWithIndex.map{case(bank,bankID)=>
        bank.io.load_vec_en     :=  Mux(bankID.U===load_vec_bank && io.load_vec_en,true.B,false.B)
        bank.io.vec_data        :=  io.vec_data
        bank.io.load_vec_addr   :=  load_vec_in_bank_matID
        // val base_index_of_req_resp_list = index*mat_num_per_bank
        bank.io.request_vec.zipWithIndex.map{case(mat_req_vec_io,matID)=>
            mat_req_vec_io  <> io.request_vec(matID*sysCfg.numBanks+bankID)
        }
        bank.io.response_vec.zipWithIndex.map{case(resp_vec_io,matID)=>
            resp_vec_io  <> io.response_vec(matID*sysCfg.numBanks+bankID)
        }
    }

    // query
    val arbiter = Module(new RRArbiter(new QueryMatReq(sysCfg), sysCfg.query_clients))
    arbiter.io.out.ready:= true.B
    for(i<-0 until sysCfg.query_clients)
    {   arbiter.io.in(i)<>io.query_mat_req(i) }

    val query_bankID=sysCfg.get_bankID(arbiter.io.out.bits.matID)
    val query_in_bank_matID=sysCfg.get_in_bank_matID(arbiter.io.out.bits.matID)                          
    
    io.mat_busy:=MuxCase(false.B,banks_list.zipWithIndex.map { case(banks,i) =>
        (query_bankID===i.U) -> banks.io.mat_busy
    })
    banks_list.foreach{bank=>
        bank.io.query_matID:=query_in_bank_matID
    }

}