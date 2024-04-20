package PIC

import chisel3._
import chisel3.util._
import ModeInfo._

class Mat(kernal_configs:PolymorPIC_Kernal_Config,pic:Boolean) extends Module 
{
    val cache_addr_len=log2Ceil(kernal_configs.wordlineNums*kernal_configs.arrayNums)
    val arrayRows=kernal_configs.wordlineNums
    val io = IO(new Bundle {
        // Normal Cache wire
        val cache_enable = Input(Bool()) // 选subarrray
        val cache_write = Input(Bool())
        val cache_addr = Input(UInt(cache_addr_len.W))
        val cache_dataIn = Input(UInt(kernal_configs.bitlineNums.W))
        val cache_dataOut = Output(UInt(kernal_configs.bitlineNums.W))

        // set up io
        val set_up_io=new SetUpIO(kernal_configs)

        // Vector load
        val load_vec_en =Input(Bool())
        val vec_data=Input(UInt((kernal_configs.bitlineNums).W))

        val request_vec=Decoupled(new RequestAddress(kernal_configs))
        val response_vec=Flipped(Decoupled(Bool()))

        // busy state
        val busy=Output(Bool())
    })

    if(pic)
    {
        val array_list = Seq.fill(4)(Module(new PolyArray(kernal_configs)))
        val controller=Module(new Controller(kernal_configs))

        // data IO
        val read_m_array_row_id=controller.io.read_M_ArrayAddr(2+log2Ceil(arrayRows)-3,0)
        val write_m_array_row_id=controller.io.write_M_ArrayAddr(2+log2Ceil(arrayRows)-3,0)
        val access_m_array_row_id=Mux(controller.io.read_M_Array_En,read_m_array_row_id,write_m_array_row_id)
        array_list.zipWithIndex.map{case(array,index)=>
            val cache_mode=controller.io.arrayCacheMode
            val array_mode=controller.io.arrayMode(index)
            val if_read_this_m_array= controller.io.read_M_ArrayAddr(2+log2Ceil(arrayRows)-1,2+log2Ceil(arrayRows)-2)===index.U && controller.io.read_M_Array_En===true.B
            val if_write_this_m_array= controller.io.write_M_ArrayAddr(2+log2Ceil(arrayRows)-1,2+log2Ceil(arrayRows)-2)===index.U && controller.io.write_M_Array_En===true.B
            val if_normal_cache_enable= (index.U===io.cache_addr(cache_addr_len-1,cache_addr_len-2)) && io.cache_enable
            
            array.io.cache_enable:=Mux(cache_mode,if_normal_cache_enable,Mux(array_mode===PICMode_Mem,if_read_this_m_array || if_write_this_m_array,controller.io.read_C_ArrayEn(index)))
            array.io.cache_write:=Mux(cache_mode,io.cache_write,Mux(array_mode===PICMode_Mem,if_write_this_m_array,false.B))
            array.io.cache_addr:=Mux(cache_mode,io.cache_addr(cache_addr_len-3,0),Mux(array_mode===PICMode_Mem,access_m_array_row_id,controller.io.read_C_ArrayAddr))
            array.io.dataIn:=Mux(cache_mode,io.cache_dataIn,controller.io.dataOut_to_M_array)
        }
        io.cache_dataOut:=MuxLookup(RegEnable(io.cache_addr(cache_addr_len-1,cache_addr_len-2),!io.cache_write && io.cache_enable), 0.U, array_list.zipWithIndex.map { case (array, index) =>
            (index.U -> array.io.dataOut)
        })

        // Vector
        val vec_buf=RegInit(0.U((kernal_configs.bitlineNums).W))
        when(io.load_vec_en===true.B)
        {vec_buf:=io.vec_data}
        array_list.zipWithIndex.map{case(array,index)=>
            array.io.left_vector:=vec_buf
        }

        // Controller request vector
        controller.io.request_vec<>io.request_vec
        controller.io.response_vec<>io.response_vec
        // Controller set up
        controller.io.set_up_io:=io.set_up_io
        // Controller and array state
        array_list.zipWithIndex.map{case(array,index)=>
            array.io.mode:=controller.io.arrayMode(index)
        }

        // Mac flags
        array_list.zipWithIndex.map{case(array,index)=>
            array.io.signed:=controller.io.signed
            array.io.left_shift_bias:=controller.io.left_shift_bias(index)
        }

        // Control signals
        array_list.zipWithIndex.map{case(array,index)=>
            array.io.mode:=controller.io.arrayMode(index)
        }
        
        // M-array
        val m_array_id=controller.io.read_M_ArrayAddr(2+log2Ceil(arrayRows)-1,2+log2Ceil(arrayRows)-2)
        val read_M_array_En=controller.io.read_M_Array_En
        val m_arrayID=RegInit(0.U(2.W))
        when(controller.io.read_M_Array_En){m_arrayID:=m_array_id}
        // controller.io.dataIn_from_M_array:=MuxCase(1.U,array_list.zipWithIndex.map { case(array,i) =>
        //           (RegEnable(m_array_id,read_M_array_En)===i.U && isPICMode_Mem(controller.io.arrayMode(i))) -> array.io.dataOut
        // })
        controller.io.dataIn_from_M_array:=MuxCase(1.U,array_list.zipWithIndex.map { case(array,i) =>
                (m_arrayID===i.U && isPICMode_Mem(controller.io.arrayMode(i))) -> array.io.dataOut
        })

        // mac sum
        val mac_sum = array_list.map(_.io.mac_dataOut).reduce(_ + _)
        controller.io.sum_of_mac:=mac_sum

        // busy state
        io.busy:=controller.io.busy
    }
    else
    {
        // Only need memory
        val mem=Module(new NormalArray(kernal_configs))
        mem.io.cache_enable:=io.cache_enable
        mem.io.cache_write:=io.cache_write
        mem.io.cache_addr:=io.cache_addr
        mem.io.dataIn:=io.cache_dataIn
        io.cache_dataOut:=mem.io.dataOut

        // disable other ports
        io.request_vec.valid:=false.B
        io.response_vec.ready:=false.B
        io.busy:=true.B
        io.request_vec.bits.subarrayID_rowID:=DontCare
    }


}
