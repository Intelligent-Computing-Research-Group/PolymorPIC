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



class MatTracer(sysCfg:Sys_Config) extends Module
{

    val io=IO(new Bundle{

        // 查询每个mat的状态
        val query_mat_req=Decoupled(new QueryMatReq(sysCfg))
        val mat_busy=Input(Bool())

        // Trace
        val rec_req= Decoupled(new RecReq(sysCfg))
    })

    val begin_matID=sysCfg.first_matID
    val end_matID=sysCfg.last_matID

    val query_mat_ptr=RegInit(begin_matID.U(log2Ceil(sysCfg.total_valid_mats).W))
    val mat_busy_rec_vec=RegInit(VecInit(Seq.fill(sysCfg.total_valid_mats){false.B}))
    val mat_busy_rec_choose=WireInit(0.U(log2Ceil(sysCfg.total_valid_mats).W))

    val query :: send_trace :: Nil = Enum(2)
    val trace_state = RegInit(query)

    io.query_mat_req.valid:= (trace_state===query)
    io.rec_req.valid := (trace_state===send_trace)
    
    for(i<-0 until sysCfg.total_valid_mats)
    {
        when(query_mat_ptr===i.U)
        {
            mat_busy_rec_choose:=mat_busy_rec_vec(i)
        }
    }

    // From idle to busy
    val start = ((mat_busy_rec_choose===false.B) & (io.mat_busy===true.B))
    // From busy to idle
    val end = ((mat_busy_rec_choose===true.B) & (io.mat_busy===false.B))
    // no change
    val no_change= (mat_busy_rec_choose===io.mat_busy)

    // state
    val mat_state = RegInit(0.U(io.rec_req.bits.rec_state.getWidth.W))

    io.rec_req.bits.rec_state:=mat_state
    io.rec_req.bits.picAddr:=query_mat_ptr
    io.rec_req.bits.command:=DontCare

    io.query_mat_req.bits.matID:=query_mat_ptr

    switch(trace_state)
    {
        is(query)
        {
            when(io.query_mat_req.fire)
            {
                mat_state:=Mux(no_change,mat_state,
                                Mux(start,RecStateType.START_RUN,RecStateType.END_RUN))
                trace_state:=Mux(no_change,trace_state,send_trace)
                query_mat_ptr:=Mux(no_change,Mux(query_mat_ptr===end_matID.U,begin_matID.U,query_mat_ptr+1.U),query_mat_ptr)
            }
        }
        is(send_trace)
        {
            when(io.rec_req.fire)
            {
                query_mat_ptr:=Mux(query_mat_ptr===end_matID.U,begin_matID.U,query_mat_ptr+1.U)
                trace_state:=query

                // update state
                for(i<-0 until sysCfg.total_valid_mats)
                {
                    when(query_mat_ptr===i.U)
                    {
                        mat_busy_rec_vec(i):= !mat_busy_rec_vec(i)
                    }
                }
            }
        }
    }

    val traceClient_exe=new TraceClient(ClientName="EXE")
    sysCfg.traceCfg.helper.addTraceClient(traceClient_exe)

}

