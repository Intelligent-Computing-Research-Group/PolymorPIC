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
import chisel3.{Data, Vec}
import chisel3.util.log2Ceil
import freechips.rocketchip.util._


// The module is used to 
// guide whether the data is for the PIC part or the Cache part
class BankShellPIC(sysCfg:Sys_Config,size:Int,data:UInt) extends Module 
{
    // TODO Add check of size and data
    val io = IO(new Bundle {
        // ----------------------- Original BankIO ------------------------
        // Mem IO
        val picMem_enable = Input(Bool())
        val picMem_write = Input(Bool())
        val picMem_addr = Input(UInt((log2Ceil(sysCfg.matPerBank)+sysCfg.core_config.core_addrLen).W))
        val picMem_dataIn = Input(UInt(sysCfg.core_config.bitlineNums.W))
        // Only when the cache is not accessing this bank, this signal is true; 
        // external observation of this signal being true indicates that a request can be initiated.
        val picMem_ready=Output(Bool())

        // Set up signals
        val set_up_addr=Input(UInt(log2Ceil(sysCfg.matPerBank).W))
        val set_up_io=Input(new SetUpIO(sysCfg.core_config))

        // Load L
        val request_vec=Vec(sysCfg.matPerBank, Decoupled(new RequestAddress(sysCfg.core_config)))
        val response_vec=Vec(sysCfg.matPerBank, Flipped(Decoupled(Bool())))
        val load_vec_en =Input(Bool())
        val load_vec_addr=Input(UInt(log2Ceil(sysCfg.matPerBank).W))
        val vec_data=Input(UInt((sysCfg.core_config.bitlineNums).W))

        // Busy state of each array
        val query_matID=Input(UInt(log2Ceil(sysCfg.matPerBank).W))
        val mat_busy=Output(Bool())
        // -----------------------------------------------------------

        // ----------------------- Original CacheIO ------------------------
        val cache_enable = Input(Bool())
        val cache_write = Input(Bool())
        val cache_addr = Input(UInt((log2Ceil(sysCfg.matPerBank)+sysCfg.core_config.core_addrLen).W))
        val cache_dataIn = Input(UInt(sysCfg.core_config.bitlineNums.W))
        // -----------------------------------------------------------

        // share the same dataout
        val bank_dataOut = Output(UInt(sysCfg.core_config.bitlineNums.W))

        // ------------------ Switch Control Info ------------
        // Ways greater than this are in PIC state. For example, if this value is 7, then ways starting from 8 are in PIC state.
        val picActivated=Input(Bool())
        val cacheLevelEnd = Input(UInt(log2Ceil(sysCfg.total_levels).W))
        // ---------------------------------------------------
    })

    val bank=Module(new Bank(sys_configs=sysCfg,core_configs=sysCfg.core_config))

    io.picMem_ready:= (io.cache_enable===false.B)

    // ------------ Connection of Computation Compotents ----------

    // Setup IOs
    assert(!((!ifValidPIC_Req(sysCfg.get_levelID_from_inner_Bank_matID(io.set_up_addr))) && io.set_up_io.exec),"Can't access a mem under cache mode with PIC channel!")
    val bankSetupIOs                    =  bank.io.set_up_io
    bank.io.set_up_addr             :=  io.set_up_addr
    bankSetupIOs.exec                  :=  Mux(ifValidPIC_Req(io.set_up_addr) && io.set_up_io.exec,true.B,false.B)
    bankSetupIOs.accWidth              :=  io.set_up_io.accWidth
    bankSetupIOs._L_block_row          :=  io.set_up_io._L_block_row
    bankSetupIOs._L_precision          :=  io.set_up_io._L_precision
    bankSetupIOs._L_vec_fetch_addr     :=  io.set_up_io._L_vec_fetch_addr
    bankSetupIOs.signed_L              :=  io.set_up_io.signed_L
    bankSetupIOs.signed_R_last_exist   :=  io.set_up_io.signed_R_last_exist
    bankSetupIOs._R_base_bit           :=  io.set_up_io._R_base_bit
    bankSetupIOs._R_block_row          :=  io.set_up_io._R_block_row
    bankSetupIOs.nBuf                  :=  io.set_up_io.nBuf
    bankSetupIOs.nCal                  :=  io.set_up_io.nCal

    // mat state query
    io.mat_busy:=bank.io.mat_busy
    bank.io.query_matID:=io.query_matID

    // load L
    bank.io.request_vec<>io.request_vec
    bank.io.response_vec<>io.response_vec
    bank.io.load_vec_en:=io.load_vec_en
    bank.io.load_vec_addr:=io.load_vec_addr
    bank.io.vec_data:=io.vec_data

    // ------------------------------------------------------------

    // ------------ Connection of dataPath -------------------
    // Determine whether the Bank is accepting a response from the normal Cache mode or from the PIC request
    val is_cache_req_enable=io.cache_enable
    val invalid_cache_req= io.cache_enable&(!ifValidCache_Req(sysCfg.get_levelID_from_inner_Bank_Addr(io.cache_addr)))
    assert(!invalid_cache_req,"Cache access a PIC mode mem!")
    val invalid_PIC_req=io.picMem_enable&(!ifValidPIC_Req(sysCfg.get_levelID_from_inner_Bank_Addr(io.picMem_addr)))
    assert(!invalid_PIC_req,"Can't access a mem under cache mode with PIC channel!")
    
    // Always give priority to normal cache mode requests
    bank.io.cache_enable:=Mux(is_cache_req_enable,io.cache_enable,io.picMem_enable)
    bank.io.cache_write:=Mux(is_cache_req_enable,io.cache_write,io.picMem_write)
    bank.io.cache_addr:=Mux(io.cache_enable,io.cache_addr,io.picMem_addr)
    bank.io.cache_dataIn:=Mux(is_cache_req_enable,io.cache_dataIn,io.picMem_dataIn)

    // DataOut
    io.bank_dataOut:=bank.io.cache_dataOut
    // -------------------------------------------------------

    def ifValidPIC_Req(levelID:UInt):Bool = {
        (levelID>io.cacheLevelEnd)&io.picActivated
    }

    def ifValidCache_Req(levelID:UInt):Bool = {
        levelID<=io.cacheLevelEnd
    }

}