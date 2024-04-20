package PIC

import chisel3._ // VecInit
import chisel3.util._ // MuxCase
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._ // LazyModule
import freechips.rocketchip.rocket._ // cache 接口
import freechips.rocketchip.tilelink._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.Buffer


class RecData(sysCfg:Sys_Config) extends Bundle {
    val time_stamp=UInt(48.W)
    val module_ID=UInt(8.W)
    val state=UInt(8.W)
}

class RecReq(sysCfg:Sys_Config) extends Bundle {
    val rec_state=UInt(log2Ceil(sysCfg.traceCfg.state_count).W)
    val picAddr=UInt(sysCfg.accessBankFullAddr_sigLen.W)
    val command=UInt(sysCfg.traceCfg.custom_filed_length.W)
}

class RecSaveReq(sysCfg:Sys_Config) extends Bundle {
    val pic_addr=UInt(sysCfg.accessBankFullAddr_sigLen.W)
}

class TraceRec(sysCfg:Sys_Config) extends Module
{

    val io=IO(new Bundle{
        // client
        val rec_request=Vec(sysCfg.traceCfg.client_num, Flipped(Decoupled(new RecReq(sysCfg))))

        // Store Rec Data
        val save_req=Flipped(Decoupled(new RecSaveReq(sysCfg)))
        val store_busy=Output(Bool())

        // write array
        val accessArray=Decoupled(new ReqPackage(sysCfg))
    })

    // memory
    val mem=SyncReadMem(sysCfg.traceCfg.trace_depth,UInt(64.W))
    // time stamp
    val time_stamp=RegInit(0.U(sysCfg.traceCfg.trace_time_stmp_field_len.W))
    time_stamp:=time_stamp+1.U
    // record num
    val rec_ptr=RegInit(0.U(16.W))
    val mem_ptr=RegInit(0.U(16.W))

    // arb
    val arbiter = Module(new RRArbiter(new RecReq(sysCfg), sysCfg.traceCfg.client_num))

    for(i<-0 until sysCfg.traceCfg.client_num)
    {   arbiter.io.in(i)<>io.rec_request(i) }

    // 表明接收到了一个请求
    val clientID=arbiter.io.chosen
    val clientState=arbiter.io.out.bits.rec_state
    val picAddr=arbiter.io.out.bits.picAddr
    val pad_zero=64-picAddr.getWidth-time_stamp.getWidth-clientState.getWidth-clientID.getWidth
    val general_record_val=Cat(0.U(pad_zero.W),
                                picAddr,
                                time_stamp,
                                clientState,
                                clientID)
    val custom_record_val_reg=RegInit(0.U(sysCfg.traceCfg.custom_filed_length.W))
    val final_record=Cat(rec_ptr,"hFFFFFFFF".U(32.W))

    // readin state
    val write_1 :: write_2 :: Nil = Enum(2)
    val in_state=RegInit(write_1)
    arbiter.io.out.ready:= (in_state===write_1)
    
    // readout state
    val idle :: read_mem :: write_array :: Nil = Enum(3)
    val out_state=RegInit(idle)

    switch(in_state)
    {
        is(write_1)
        {
            when(arbiter.io.out.fire && out_state===idle)
            {
                val write_custom = (arbiter.io.out.bits.rec_state===RecStateType.START_RUN & clientID=/=sysCfg.traceCfg.ID_Map("EXE").U)
                custom_record_val_reg:=arbiter.io.out.bits.command
                mem.write(mem_ptr,general_record_val)
                mem_ptr:=mem_ptr+1.U
                rec_ptr:= Mux(write_custom,rec_ptr,rec_ptr+1.U)
                in_state:=Mux(write_custom,write_2,write_1)
            }
        }
        is(write_2)
        {
            mem.write(mem_ptr,custom_record_val_reg)
            rec_ptr:=rec_ptr+1.U
            mem_ptr:=mem_ptr+1.U
            in_state:=write_1
        }
    }

    val readmem_en=WireInit(false.B)
    val write_array_addr_base=RegInit(0.U(sysCfg.accessBankFullAddr_sigLen.W))
    val write_array_addr_ptr=RegInit(0.U(sysCfg.accessBankFullAddr_sigLen.W))

    io.store_busy:= (out_state=/=idle)

    io.accessArray.bits.addr:=write_array_addr_base+write_array_addr_ptr
    io.accessArray.bits.optype:=AccessArrayType.WRITE
    io.accessArray.bits.dataWrittenToBank:=mem.read(write_array_addr_ptr, readmem_en)
    io.accessArray.valid:=false.B

    io.save_req.ready:= (out_state===idle)
    switch(out_state)
    {
        is(idle)
        {
            when(io.save_req.fire)
            {   
                write_array_addr_base:=io.save_req.bits.pic_addr
                write_array_addr_ptr:=0.U
                mem.write(mem_ptr,final_record)
                out_state:=read_mem 
            }
        }
        is(read_mem)
        {
            readmem_en:=true.B
            out_state:=write_array
        }
        is(write_array)
        {
            io.accessArray.valid:=true.B
            when(io.accessArray.fire)
            {
                write_array_addr_ptr:=write_array_addr_ptr+1.U
                out_state:=Mux(write_array_addr_ptr===sysCfg.traceCfg.trace_depth.U,idle,read_mem)
            }
        }
    }


}

