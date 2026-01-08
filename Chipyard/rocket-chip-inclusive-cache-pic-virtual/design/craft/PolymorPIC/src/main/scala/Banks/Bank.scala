// Copyright (c) [Year] [name of copyright holder]
// [Software Name] is licensed under Mulan PSL v2.
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

class Bank(sys_configs:Sys_Config,core_configs:PolymorPIC_Kernal_Config) extends Module 
{
    val mat_num=sys_configs.matPerBank
    val io = IO(new Bundle {
        // Cache IO
        val cache_enable = Input(Bool()) // select subarray
        val cache_write = Input(Bool())
        val cache_addr = Input(UInt((log2Ceil(mat_num)+core_configs.core_addrLen).W))
        val cache_dataIn = Input(UInt(core_configs.bitlineNums.W))
        val cache_dataOut = Output(UInt(core_configs.bitlineNums.W))

        // Set up signals
        val set_up_addr=Input(UInt(log2Ceil(mat_num).W))
        val set_up_io=Input(new SetUpIO(sys_configs.core_config))

        // Load L
        val request_vec=Vec(mat_num, Decoupled(new RequestAddress(core_configs)))
        val response_vec=Vec(mat_num, Flipped(Decoupled(Bool())))
        val load_vec_en =Input(Bool())
        val load_vec_addr=Input(UInt(log2Ceil(mat_num).W))
        val vec_data=Input(UInt((core_configs.bitlineNums).W))

        // Busy state of each array
        val query_matID=Input(UInt(log2Ceil(mat_num).W))
        val mat_busy=Output(Bool())
  })

    // Mat list
    // val mat_list = Seq.fill(mat_num)(Module(new Mat(core_configs)))
    val mat_list = Seq.tabulate(mat_num) { index =>
      val if_pic= (index>=sys_configs.in_bank_first_matID)
      Module(new Mat(kernal_configs=core_configs, pic=if_pic))
    }


    // set_up_io connections
    mat_list.zipWithIndex.map{case(mat,index)=>
        val set_up_io           =   mat.io.set_up_io
        set_up_io.exec             :=  Mux(index.U===io.set_up_addr && io.set_up_io.exec,true.B,false.B)
        set_up_io.accWidth         :=  io.set_up_io.accWidth
        set_up_io.signed_L        :=  io.set_up_io.signed_L
        set_up_io.signed_R_last_exist        :=  io.set_up_io.signed_R_last_exist
        set_up_io._L_block_row     :=  io.set_up_io._L_block_row
        set_up_io._L_precision     :=  io.set_up_io._L_precision
        set_up_io._L_vec_fetch_addr:=  io.set_up_io._L_vec_fetch_addr
        set_up_io._R_base_bit       :=  io.set_up_io._R_base_bit
        set_up_io._R_block_row     :=  io.set_up_io._R_block_row
        set_up_io.nBuf              :=  io.set_up_io.nBuf
        set_up_io.nCal              :=  io.set_up_io.nCal
    }
    
    // data_io connections
    val access_mat_ID=io.cache_addr(core_configs.core_addrLen+log2Ceil(mat_num)-1,core_configs.core_addrLen)
    val cache_addr_mat_inner=io.cache_addr(core_configs.core_addrLen-1,0)
    mat_list.zipWithIndex.map{case(mat,index)=>
        mat.io.cache_enable :=  Mux(index.U===access_mat_ID && io.cache_enable,true.B,false.B)
        mat.io.cache_write  :=  Mux(index.U===access_mat_ID && io.cache_write,true.B,false.B)
        mat.io.cache_addr   :=  cache_addr_mat_inner
        mat.io.cache_dataIn :=  io.cache_dataIn
    }

    // Chisel 5
    // io.cache_dataOut:=MuxLookup(RegEnable(access_mat_ID,!io.cache_write && io.cache_enable), 0.U)( mat_list.zipWithIndex.map { case (mat, index) =>
    //     (index.U -> mat.io.dataOut)
    // })
    io.cache_dataOut:=MuxLookup(RegEnable(access_mat_ID,!io.cache_write && io.cache_enable), 0.U, mat_list.zipWithIndex.map { case (mat, index) =>
        (index.U -> mat.io.cache_dataOut)
    })


    // load L
    mat_list.zipWithIndex.map{case(mat,index)=>
        mat.io.request_vec<>io.request_vec(index)
        mat.io.response_vec<>io.response_vec(index)
        mat.io.load_vec_en:=Mux(io.load_vec_addr===index.U && io.load_vec_en, true.B,false.B)
        mat.io.vec_data:=io.vec_data
    }


    // mat state query
    io.mat_busy:=MuxCase(false.B,mat_list.zipWithIndex.map { case(mat,i) =>
        (io.query_matID===i.U) -> mat.io.busy
    })
   

}





object DescribedPolymorPIC {
  def apply[T <: Data](
    name: String,
    desc: String,
    size: BigInt, // depth
    data: T,
    sysCfg:Sys_Config
  ): Bank = {

    assert(size==sysCfg.sizeBank_64b,s"Cache size not match!")
    assert(data.getWidth==64,s"Cache size not match!")
    val mem = Module(new Bank(sys_configs=sysCfg,core_configs=sysCfg.core_config))

    mem.suggestName(name)

    val granWidth = data match {
      case v: Vec[_] => v.head.getWidth
      case d => d.getWidth
    }

    val uid = 0

    Annotated.srams(
      component = mem,
      name = name,
      address_width = log2Ceil(size),
      data_width = data.getWidth,
      depth = size,
      description = desc,
      write_mask_granularity = granWidth
    )

    mem
  }
}