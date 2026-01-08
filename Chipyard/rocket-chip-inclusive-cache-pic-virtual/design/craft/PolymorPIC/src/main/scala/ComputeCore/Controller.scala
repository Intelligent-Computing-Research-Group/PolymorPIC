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
import ModeInfo._
import CalInfo._

class RequestAddress(kernal_configs:PolymorPIC_Kernal_Config) extends Bundle {
  val subarrayID_rowID = UInt((kernal_configs._L_vec_fetch_addrLen).W) 
}

class SetUpIO(cfg:PolymorPIC_Kernal_Config) extends Bundle
{
    val exec=Bool()
    val accWidth=Bool()
    val signed_L=Bool()
    val signed_R_last_exist=Bool()
    val _L_precision=UInt(log2Ceil(8).W)
    val _L_block_row=UInt((log2Ceil(cfg.max_L_rows_per_block)+1).W)
    val _L_vec_fetch_addr=UInt((cfg._L_vec_fetch_addrLen).W)
    val _R_block_row=UInt(log2Ceil(cfg.wordlineNums).W)
    val _R_base_bit=UInt(log2Ceil(8).W)
    val nBuf=UInt(2.W)
    val nCal=UInt(2.W)

    def init(): Unit =
    {
        exec:=false.B
    }
}

class Controller(kernal_configs:PolymorPIC_Kernal_Config) extends Module
{
    
    val _L_block_row_signalLen=log2Ceil(kernal_configs.max_L_rows_per_block)
    val precision_sigLen=log2Ceil(7+7)
    val arrayRows=kernal_configs.wordlineNums
    val arrayRowsAdrLen=log2Ceil(arrayRows)
    val vecLen=kernal_configs.bitlineNums
    
    val io = IO(new Bundle {
        // exec & setup
        val set_up_io=Input(new SetUpIO(kernal_configs))

        // Mac parameters
        val left_shift_bias=Output(Vec(4,UInt(precision_sigLen.W)))

        // M_array operation
        val dataIn_from_M_array=Input(UInt(vecLen.W))
        val dataOut_to_M_array=Output(UInt(vecLen.W))
        val read_M_ArrayAddr=Output(UInt((log2Ceil(4)+log2Ceil(arrayRows)).W))
        val write_M_ArrayAddr=Output(UInt((log2Ceil(4)+log2Ceil(arrayRows)).W))
        val read_M_Array_En=Output(Bool())
        val write_M_Array_En=Output(Bool())

        val read_C_ArrayAddr=Output(UInt(arrayRowsAdrLen.W))  // Only C-array
        val read_C_ArrayEn=Output(Vec(4,Bool()))  // Only C-array
        val arrayMode=Output(Vec(4,UInt(Mode_bitWidth.W)))
        val arrayCacheMode=Output(Bool())

        // Mac results
        val sum_of_mac=Input(UInt(32.W))

        // Auto Load L
        val request_vec=Decoupled(new RequestAddress(kernal_configs))
        val response_vec=Flipped(Decoupled(Bool()))

        // Param to MAC unit
        val signed=Output(Vec(4,Bool()))

        // Busy
        val busy=Output(Bool())
    })


    // Init
    io.read_C_ArrayEn.zipWithIndex.map{case(en,index)=>en:=false.B}
    io.response_vec.ready:=false.B
    io.request_vec.bits.subarrayID_rowID:=DontCare
    io.request_vec.valid:=false.B


    // mainState
    val main_idle :: pre_read_M_array :: cal ::post_process::pre_check :: main_wait_L  :: Nil = Enum(6)
    val mainState=RegInit(main_idle)
    io.busy:= (mainState=/=main_idle)

    // read M-array then write to readBuf state
    val send_L_req :: wait_L_resp :: start_next :: Nil = Enum(3)
    val load_vec_state=RegInit(send_L_req)


    ///// ####### Control Registers & Wires####### ////
    // First Slice Needn't read partial sum (M-Array is empty)
    val if_read_partial_sum_reg=RegInit(false.B)
    // Read/write which M-Array and row (Array-Row-Address)   2->which array
    val read_M_Array_RowAdr_reg = RegInit(0.U((2+arrayRowsAdrLen).W))
    val write_M_Array_RowAdr_reg = RegInit(0.U((2+arrayRowsAdrLen).W))
    val read_M_array_selectedID= read_M_Array_RowAdr_reg(arrayRowsAdrLen+2-1,arrayRowsAdrLen)
    val write_M_array_selectedID= write_M_Array_RowAdr_reg(arrayRowsAdrLen+2-1,arrayRowsAdrLen)
    // Last rowID in C-array. Mac stopped after reading this
    val _C_array_EndPtr = RegInit(0.U(arrayRowsAdrLen.W))
    val read_C_ArrayAddr_reg = RegInit(0.U(arrayRowsAdrLen.W))
    // Left Shift bias
    val bitID_R=RegInit(VecInit(Seq.fill(4)(0.U(precision_sigLen.W))))
    // signed
    val lastBitR_bidID=RegInit(0.U(log2Ceil(8).W))
    val signed_L=RegInit(false.B)
    val signed_R_last_exist=RegInit(false.B)

    // Read/Write Buffer Vec
    val wBuf=RegInit(VecInit(Seq.fill(kernal_configs.segNum_in_per_word)(0.U(16.W))))
    val rWire=WireInit(VecInit(Seq.fill(kernal_configs.segNum_in_per_word)(0.U(16.W))))
    val read_M_array_En_wire=WireInit(false.B)
    val write_M_array_En_wire=WireInit(false.B)
    val write_wBuf_wire=WireInit(false.B)
    val write_wBuf=RegNext(write_wBuf_wire)
    // buf for marray's readout
    val rBuf=RegInit(VecInit(Seq.fill(kernal_configs.segNum_in_per_word)(0.U(16.W))))
    val _M_array_dout_valid=RegNext(read_M_array_En_wire)

    val wbuf_ptr_reg=RegInit(0.U(log2Ceil(kernal_configs.segNum_in_per_word).W))
    // Array Mode Reg
    val arrayMode_reg=RegInit(VecInit(Seq.fill(4)(0.U(Mode_bitWidth.W))))
    val arrayCacheMode_reg=RegInit(true.B)
    // AccWidth Reg
    val accWidth_reg=RegInit(ACC_32BIT)
    val buf_ptr_inc=Mux(accWidth_reg===ACC_32BIT,2.U,1.U)
    // buf 
    val writeBuf_ptr_end=(kernal_configs.segNum_in_per_word).U-1.U-buf_ptr_inc  // Not the last
    // L vector ptr
    val _L_vec_ptr_cur=RegInit(0.U((_L_block_row_signalLen+1).W))
    val _L_block_row=RegInit(0.U((_L_block_row_signalLen+1).W))
    val _L_precision_reg=RegInit(0.U(precision_sigLen.W))
    val _L_bitSlice_ID_ptr=RegInit(0.U(precision_sigLen.W))
    val _L_vec_addr=RegInit(0.U((kernal_configs._L_vec_fetch_addrLen).W))
    // First slice
    val is_first_slice=RegInit(true.B)
    // State Flag
    val is_last_C_array_row=(read_C_ArrayAddr_reg===_C_array_EndPtr)
    val is_last_L_block_row=(_L_vec_ptr_cur===_L_block_row)
    val is_wBuf_ptr_end=(wbuf_ptr_reg===((kernal_configs.segNum_in_per_word).U-1.U))
    val skip_read_M_array=RegInit(false.B)

    ///// ####### Sign ####### ////
    val signed_R_vec=WireInit(VecInit(Seq.fill(4)(false.B)))
    for(i <-0 until 4)
    {
        val isCal_isSignedLastExist = (arrayMode_reg(i)===ModeInfo.PICMode_Mac)&signed_R_last_exist
        signed_R_vec(i):= Mux(isCal_isSignedLastExist,lastBitR_bidID===bitID_R(i),false.B)
        io.signed(i):=signed_R_vec(i)^(signed_L&(_L_precision_reg===_L_bitSlice_ID_ptr))
    }

    ///// ####### Connection ####### ////
    io.read_C_ArrayAddr:=read_C_ArrayAddr_reg
    io.left_shift_bias.zipWithIndex.map{case(bias_out,index)=>
        bias_out:=_L_bitSlice_ID_ptr+bitID_R(index)
    }
    for(i<- 0 until kernal_configs.segNum_in_per_word)
    {
        rWire(i):=io.dataIn_from_M_array(16*i+15,16*i)
    }
    io.arrayMode:=arrayMode_reg
    io.arrayCacheMode:=arrayCacheMode_reg
    io.write_M_ArrayAddr:=write_M_Array_RowAdr_reg
    io.read_M_ArrayAddr:=read_M_Array_RowAdr_reg
    io.read_M_Array_En:=read_M_array_En_wire        // no need delay
    io.write_M_Array_En:=RegNext(write_M_array_En_wire) // need delay ref to wave form
    io.dataOut_to_M_array:=Cat(wBuf.reverse)
    when(RegNext(write_M_array_En_wire))    // after write
    {
        val init_ptr=Mux(accWidth_reg===ACC_32BIT,1.U,0.U)
        write_M_Array_RowAdr_reg:=write_M_Array_RowAdr_reg+1.U
    }

    // write wBuf
    when(write_wBuf)
    {
        // Write Buf
        when(accWidth_reg===ACC_32BIT)
        {
            for(index <- 1 until kernal_configs.segNum_in_per_word)
            {
                val _M_array_readOut = Mux(_M_array_dout_valid,Cat(rWire(index),rWire(index-1)),Cat(rBuf(index),rBuf(index-1)))
                val res_32b=Mux(is_first_slice,0.U,_M_array_readOut)+io.sum_of_mac
                when(wbuf_ptr_reg===index.U)    
                {    
                    wBuf(index):=res_32b(31,16) // HSB
                    wBuf(index-1):=res_32b(15,0)// LSB
                }
            }

        }
        .elsewhen(accWidth_reg===ACC_16BIT)
        {
            wBuf.zipWithIndex.map{case(wbuf,index)=>
                val _M_array_readOut = Mux(_M_array_dout_valid,rWire(index),rBuf(index))
                val res_16b=Mux(is_first_slice,0.U,_M_array_readOut)+io.sum_of_mac
                wbuf:=Mux(wbuf_ptr_reg===index.U,res_16b,wbuf)
            }
        }

        // Update ptr
        wbuf_ptr_reg:=wbuf_ptr_reg+buf_ptr_inc
    }

    // save marray readout to rBuf
    when(_M_array_dout_valid)
    {
        rBuf.zipWithIndex.map{case(rbuf,index)=>
                rbuf:=rWire(index)
        }
    }

    switch(mainState)
    {
        // Receive Run siginal and configuration
        is(main_idle)
        {
            // Receive exec signal
            when(io.set_up_io.exec)
            {
                // R read pointer
                _C_array_EndPtr:=io.set_up_io._R_block_row
                read_C_ArrayAddr_reg:=0.U

                // R bitID
                val bitID_R_wire_vec=WireInit(VecInit(Seq.fill(4)(0.U(log2Ceil(8).W))))
                val first_CArray_ID=io.set_up_io.nBuf
                bitID_R_wire_vec.zipWithIndex.map{case(bitID_R_wire,index)=>
                    bitID_R_wire:=Mux(index.U===io.set_up_io.nBuf,
                                        io.set_up_io._R_base_bit,
                                        Mux(index.U>io.set_up_io.nBuf,io.set_up_io._R_base_bit+(index.U-first_CArray_ID),0.U))
                }
                bitID_R:=bitID_R_wire_vec

                // Array Mode
                val working_array_num=Mux((io.set_up_io.nBuf+io.set_up_io.nCal)===0.U,4.U,io.set_up_io.nBuf+io.set_up_io.nCal)
                arrayMode_reg(0):=ModeInfo.PICMode_Mem
                arrayMode_reg(1):=Mux(io.set_up_io.nBuf>1.U,ModeInfo.PICMode_Mem,ModeInfo.PICMode_Mac)
                arrayMode_reg(2):=Mux(io.set_up_io.nBuf>2.U,ModeInfo.PICMode_Mem,Mux(working_array_num<=2.U,ModeInfo.PICMode_IdleMac,ModeInfo.PICMode_Mac))
                arrayMode_reg(3):=Mux(working_array_num<=3.U,ModeInfo.PICMode_IdleMac,ModeInfo.PICMode_Mac)

                // AccWidth
                accWidth_reg:=io.set_up_io.accWidth

                // Signed
                lastBitR_bidID:=io.set_up_io._R_base_bit+io.set_up_io.nCal-1.U
                signed_L:=io.set_up_io.signed_L
                signed_R_last_exist:=io.set_up_io.signed_R_last_exist

                // Init Buf
                wBuf.foreach{wbuf=>wbuf:=0.U}
                wbuf_ptr_reg:=Mux(io.set_up_io.accWidth===ACC_32BIT,1.U,0.U)

                // L info
                _L_vec_ptr_cur:=0.U
                _L_block_row:=io.set_up_io._L_block_row
                _L_precision_reg:=io.set_up_io._L_precision
                _L_bitSlice_ID_ptr:=0.U
                _L_vec_addr:=io.set_up_io._L_vec_fetch_addr

                is_first_slice:=true.B

                // Disable cache mode
                arrayCacheMode_reg:=false.B


                // Next state
                mainState:=main_wait_L
            }
        }
        is(pre_read_M_array)
        {
            when(!is_first_slice)
            {
                read_M_array_En_wire:=true.B
                read_M_Array_RowAdr_reg:=read_M_Array_RowAdr_reg+1.U
            }
            mainState:=cal
        }
        is(cal) //In this stage, each cycle, there is a vliad data output from mac
        {
            // read C_array
            read_C_ArrayAddr_reg:=read_C_ArrayAddr_reg+1.U
            io.read_C_ArrayEn.zipWithIndex.map{case(en,index)=>en:=Mux(isPICMode_Mac(arrayMode_reg(index)),true.B,false.B)}
            write_wBuf_wire:=true.B

            // Jump condiion
            when(read_C_ArrayAddr_reg===_C_array_EndPtr)
            {
                read_C_ArrayAddr_reg:=0.U
                mainState:=post_process
            }
            .otherwise
            {
                mainState:=cal
            }

            when(is_wBuf_ptr_end && read_C_ArrayAddr_reg=/=0.U)     
                write_M_array_En_wire:=true.B
                when(!is_first_slice)
                {
                    read_M_array_En_wire:=true.B
                    read_M_Array_RowAdr_reg:=read_M_Array_RowAdr_reg+1.U
                }
            }
        }
        // There are three scenarios when entering this state:
        // 1 is_wBuf_ptr_end: 
        //     The wBuf is completely full and must be written back. A prefetch of M_array is required.
        // 2 !is_wBuf_ptr_end (The wBuf is not yet full):
        //   2.1 !is_last_L_block_row
        //      The wBuf pointer must be maintained so that the next calculation cycle can finish filling the buffer before writing it back. No M_array prefetch is needed.
        //   2.2 is_last_L_block_row
        //      The wBuf pointer does not need to be maintained (it should be reset), and the buffer is written back immediately. A prefetch of M_array is required.
        is(post_process)
        {
            // Write back
            when(is_wBuf_ptr_end)
            {
                write_M_array_En_wire:=true.B
            }
            .otherwise
            {
                when(is_last_L_block_row)
                {
                    write_M_array_En_wire:=true.B
                    wbuf_ptr_reg:=Mux(accWidth_reg===ACC_32BIT,1.U,0.U)
                }
                .otherwise
                {skip_read_M_array:=true.B}
            }
            mainState:=pre_check
        }
        is(pre_check)
        {
            // L last row finish
            when(is_last_L_block_row)
            {
                // This is also the last bit slice of the matrix
                // Entire finish  TODO, automatically jump to the next L block
                _L_vec_ptr_cur:=0.U
                when(_L_bitSlice_ID_ptr===_L_precision_reg)
                {
                    mainState:=main_idle
                    // Restore cache state
                    arrayCacheMode_reg:=true.B 
                    // Clear regs
                    read_C_ArrayAddr_reg:=0.U
                    read_M_Array_RowAdr_reg:=0.U
                    write_M_Array_RowAdr_reg:=0.U
                }
                .otherwise // Continue calculating the next bit slice
                {
                    _L_bitSlice_ID_ptr:=_L_bitSlice_ID_ptr+1.U
                    is_first_slice:=false.B
                    // C-array pointer reset to 0
                    read_C_ArrayAddr_reg:=0.U
                    // M-array pointer reset to 0
                    read_M_Array_RowAdr_reg:=0.U
                    write_M_Array_RowAdr_reg:=0.U
                    // Buf pointer reset to 0
                    mainState:=main_wait_L
                }
            }
            // Current L slice not finished, continue filling the buffer
            .otherwise 
            {
                mainState:=main_wait_L
            }
        }
        is(main_wait_L)
        {
            switch(load_vec_state)
            {
                is(send_L_req)
                {
                    io.request_vec.valid:=true.B
                    when(io.request_vec.fire)
                    {
                        io.request_vec.bits.subarrayID_rowID:=_L_vec_addr
                        _L_vec_addr:=_L_vec_addr+1.U
                        load_vec_state:=wait_L_resp
                    }
                }
                is(wait_L_resp)
                {
                    io.response_vec.ready:=true.B
                    when(io.response_vec.fire){load_vec_state:=start_next}
                }
                is(start_next)
                {
                    load_vec_state:=send_L_req
                    _L_vec_ptr_cur:=_L_vec_ptr_cur+1.U
                    mainState:=Mux(skip_read_M_array,cal,pre_read_M_array)
                    skip_read_M_array:=false.B
                }
            }
        }
    }
}