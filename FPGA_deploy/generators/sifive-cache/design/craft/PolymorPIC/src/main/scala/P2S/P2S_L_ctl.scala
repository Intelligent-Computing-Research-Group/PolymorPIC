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
import freechips.rocketchip.util.{DontTouch}


class P2S_L_req(sysCfg:Sys_Config) extends Bundle {
    val precision=UInt(3.W)
    val next_row_offset_elem=UInt(sysCfg.offset_signLen.W)
    val base_picAddr_to_store=UInt((sysCfg.accessCacheFullAddrLen).W) 
    val base_dramAddr_to_load=UInt(sysCfg.virtualAddrLen.W)
    val _L_block_row=UInt(sysCfg._L_nRow_sigLen.W)
}

class split_data(arrayAddrLen:Int) extends Bundle {
    val split_val=UInt(64.W)
    val arrayAddr=UInt(arrayAddrLen.W)
}

class unsplit_data(dim:Int) extends Bundle {
    val unsplit_split_val=Vec(dim,Vec(8,UInt(8.W)))
}

class P2S_L_ctl(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends Module
{
    val dim=(core_configs.bitlineNums*8/64).toInt  // How many 64b each row
    val max_64b_per_access=(core_configs.bitlineNums*8/64).toInt

    val io = IO(new Bundle {
        // request from rocc
        val p2s_L_req = Flipped(Decoupled(new P2S_L_req(sysCfg)))
        val busy = Output(Bool())

        // request to dma
        val dma_read_req = Decoupled(new Load_req(sysCfg))

        // dma write data to buffer here
        val dataInAvailable = Flipped(Decoupled(Bool()))
        val dataFromLoadCtl = Input(UInt(64.W))

        // write array
        val accessArray=Decoupled(new ReqPackage(sysCfg))
    })

    // IO deaults
    io.p2s_L_req.ready:=false.B 
    io.dma_read_req.valid:= false.B
    io.dma_read_req.bits.dataDir:= DataDir.p2sLbuffer
    io.dma_read_req.bits.row:= 1.U  // One row per req
    io.dma_read_req.bits.byte_per_row:= 0.U
    io.dma_read_req.bits.offset:= DontCare
    io.dma_read_req.bits.baseAddr_DRAM:= 0.U
    io.dma_read_req.bits.baseAddr_Array:= 0.U
    io.dma_read_req.bits.padding:= false.B
    io.dma_read_req.bits.padInfo:= DontCare
    io.accessArray.valid:=false.B
    io.accessArray.bits.addr:=0.U
    io.accessArray.bits.optype:=AccessArrayType.WRITE
    io.accessArray.bits.dataWrittenToBank:=0.U

    // Store one row of original L data, regardless of precision, it is enough
    val regArray = Seq.fill(dim, 8)(Reg(UInt(8.W)))


    val split_data_queue=Module(new Queue(new split_data(sysCfg.accessCacheFullAddrLen), 2))
    split_data_queue.io.enq.valid:=false.B
    split_data_queue.io.enq.bits.split_val:=0.U
    split_data_queue.io.enq.bits.arrayAddr:=0.U
    split_data_queue.io.deq.ready:=false.B

    // Register
    val precision=RegInit(0.U(3.W))
    val bits_per_ele=8.U
    val base_dram_addr=RegInit(0.U(sysCfg.virtualAddrLen.W))
    val base_picAddr=RegInit(0.U((sysCfg.accessCacheFullAddrLen).W))
    val pic_write_ptr=RegInit(0.U((sysCfg.accessCacheFullAddrLen).W))
    val next_row_offset_elem=RegInit(0.U(sysCfg.offset_signLen.W))
    val next_row_offset_dram=(next_row_offset_elem*bits_per_ele)>>log2Ceil(8)
    val bit_ptr=RegInit(0.U(3.W))
    val _L_block_row=RegInit(0.U(sysCfg._L_nRow_sigLen.W))
    val _L_block_row_ptr=RegInit(0.U(sysCfg._L_nRow_sigLen.W))
    val next_slice_offset_pic=_L_block_row

    val dataIn_reformat_2b=WireInit(VecInit(Seq.fill(32)(0.U(2.W))))
    for(i<- 0 until 32)
    {dataIn_reformat_2b(i):=io.dataFromLoadCtl(i*2+1,i*2)}
    val dataIn_reformat_4b=WireInit(VecInit(Seq.fill(16)(0.U(4.W))))
    for(i<- 0 until 16)
    {dataIn_reformat_4b(i):=io.dataFromLoadCtl(i*4+3,i*4)}
    val dataIn_reformat_8b=WireInit(VecInit(Seq.fill(8)(0.U(8.W))))
    for(i<- 0 until 8)
    {dataIn_reformat_8b(i):=io.dataFromLoadCtl(i*8+7,i*8)}

    // dontTouch(dataIn_reformat_8b)


    val ptr_8_elem = RegInit(0.U(log2Ceil(8+1).W))

    // state
    val p2s_idle :: req_dma :: rev_L_data_one_row :: split_and_enq :: Nil = Enum(4)
    val p2s_state=RegInit(p2s_idle)
    io.dataInAvailable.ready:=(p2s_state===rev_L_data_one_row)
    
    val queue_empty  ::write_array :: Nil = Enum(2)
    val deq_state=RegInit(queue_empty)

    io.busy:=  (p2s_state=/=p2s_idle)  || split_data_queue.io.deq.valid 


   

    switch(p2s_state)
    {
        is(p2s_idle)
        {
            io.p2s_L_req.ready:=true.B

            when(io.p2s_L_req.fire)
            {
                val req_bits=io.p2s_L_req.bits
                next_row_offset_elem:=req_bits.next_row_offset_elem
                assert((next_row_offset_elem*bits_per_ele&"b111".U)===0.U,"Offset is not times of byte, padding may be needed.")
                base_dram_addr:=req_bits.base_dramAddr_to_load
                base_picAddr:=req_bits.base_picAddr_to_store
                pic_write_ptr:=req_bits.base_picAddr_to_store
                precision:=req_bits.precision
                _L_block_row:=req_bits._L_block_row
                _L_block_row_ptr:=0.U

                p2s_state:=req_dma
            }

        }
        is(req_dma)       // read a row of block in R_block
        {
            io.dma_read_req.valid:=true.B
            when(io.dma_read_req.fire)
            {
                io.dma_read_req.bits.byte_per_row:= core_configs.bitlineNums.U
                io.dma_read_req.bits.offset:= next_row_offset_elem
                io.dma_read_req.bits.baseAddr_DRAM:=base_dram_addr
                base_dram_addr:=base_dram_addr+next_row_offset_dram
                p2s_state:=rev_L_data_one_row
            }
        }
        is(rev_L_data_one_row)
        {
            // Immediately write the received 8 bytes into the unsplit buffer; one pointer is equivalent to 8 small registers.
            // one dim is a 64b
            val ptr_8_elem_inc=1.U
            val valid_dim_id_mask="b111".U
            when(io.dataInAvailable.fire)
            {
                // Store it in buf，ptr++
                for(dim_ptr <- 0 until dim)
                {
                    // 8 bytes each time
                    when((dim_ptr.U & valid_dim_id_mask)===ptr_8_elem)
                    {
                        for(i <- 0 until 8)
                        {
                            regArray(dim_ptr)(i):=dataIn_reformat_8b(i)
                        }
                    }
                }
                
                ptr_8_elem:=ptr_8_elem+ptr_8_elem_inc
                when(ptr_8_elem===(dim-1).U)
                {
                    ptr_8_elem:=0.U
                    bit_ptr:=0.U
                    pic_write_ptr:=base_picAddr+_L_block_row_ptr
                    p2s_state:=split_and_enq
                }
            }
        }
        is(split_and_enq)  // Split and enqueue
        {
            split_data_queue.io.enq.valid:=true.B
            when(split_data_queue.io.enq.fire)
            {

                split_data_queue.io.enq.bits.arrayAddr:=pic_write_ptr
                split_data_queue.io.enq.bits.split_val:=extractBits(regArray,bit_ptr,dim)

                // jump to next slice
                pic_write_ptr:=pic_write_ptr+next_slice_offset_pic
                bit_ptr:=bit_ptr+1.U


                when(bit_ptr===precision)
                {
                    _L_block_row_ptr:=_L_block_row_ptr+1.U
                    p2s_state:=Mux(_L_block_row_ptr===_L_block_row-1.U,p2s_idle,req_dma)
                    
                }
            }
        }
    }



    val is_queue_empty = !split_data_queue.io.deq.valid
    val is_queue_has_val = split_data_queue.io.deq.valid
    switch(deq_state)
    {
        is(queue_empty)
        {
            // queue has data
            when(split_data_queue.io.deq.valid)
            {
                deq_state:=write_array
            }
        }
        is(write_array)
        {
            io.accessArray.valid:=is_queue_has_val
            when(io.accessArray.fire)
            {
                // deq
                split_data_queue.io.deq.ready:=true.B
                io.accessArray.bits.dataWrittenToBank:=split_data_queue.io.deq.bits.split_val
                io.accessArray.bits.optype:=AccessArrayType.WRITE
                io.accessArray.bits.addr:=split_data_queue.io.deq.bits.arrayAddr
            }

            when(is_queue_empty)
            {
                deq_state:=queue_empty
            }

        }
    }


    def extractBits(array: Seq[Seq[UInt]], bit: UInt, dim:Int): UInt = {
        val res=WireInit(VecInit(Seq.fill(64)(0.U(8.W))))
        for(i<-0 until 64)
        {
            res(i):=array((i/8).toInt)((i%8).toInt)
        }

        Cat(res.map(_(bit)).reverse)
    }
        

}