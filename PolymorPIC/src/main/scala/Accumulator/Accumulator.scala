package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._

class Accumu_req(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config) extends Bundle 
{
    val src_arrayNum=UInt(log2Ceil(sysCfg.acc_res_num+1).W)
    val base_src_picAddr=UInt((sysCfg.accessBankFullAddr_sigLen).W)
    val dest_picAddr=UInt((sysCfg.accessBankFullAddr_sigLen).W)
    val row_num=UInt(log2Ceil(3*core_configs.wordlineNums).W)
    val load_dest=Bool()
    val bitWidth=Bool()
}

class Accumulator(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends Module
{
    val arrayAddrLen=log2Ceil(sysCfg.total_array_nums)+log2Ceil(core_configs.arrayNums*core_configs.wordlineNums)
    val io = IO(new Bundle {
        // request from rocc
        val accumulate_req = Flipped(Decoupled(new Accumu_req(sysCfg,core_configs)))
        val busy = Output(Bool())

        // connect to bank
        val accessArrayReq=Decoupled(new ReqPackage(sysCfg))
        val dataReadFromBank=Input(UInt(64.W))

         // Trace
        val rec_req= if (sysCfg.en_trace) Some(Decoupled(new RecReq(sysCfg))) else None
    })

    val arrayNum_reg=RegInit(0.U(log2Ceil(sysCfg.acc_res_num+1).W))
    val base_src_picAddr_reg=RegInit((0.U((sysCfg.accessBankFullAddr_sigLen).W)))
    val dest_picAddr_reg=RegInit((0.U(log2Ceil(core_configs.total_arrays*core_configs.wordlineNums).W)))
    val accumulator_buf=RegInit(0.U(64.W))
    val total_rowNum=RegInit(0.U(log2Ceil(3*core_configs.wordlineNums).W))
    val row_ptr=RegInit(0.U(13.W))
    val read_op_array_ptr=RegInit(0.U(log2Ceil(sysCfg.acc_res_num+1).W)) // read_op_array_ptr = 0 1 2 .. total-1
    val finishRead = (read_op_array_ptr===arrayNum_reg)
    val load_dest_reg=RegInit(true.B)
    val addr_offset=read_op_array_ptr<<log2Ceil(core_configs.wordlineNums*4)
    val selected_array_row_addr=base_src_picAddr_reg+row_ptr+addr_offset
    val bitWidth_reg=RegInit(CalInfo.ACC_32BIT)

    val proc_idle ::load_dest::rev_dest:: proc_read :: proc_write_back :: send_trace::Nil = Enum(6)
    val proc_state = RegInit(proc_idle)
    io.accumulate_req.ready:= (proc_state===proc_idle)
    io.accessArrayReq.valid:= false.B
    io.accessArrayReq.bits.dataWrittenToBank:= 0.U
    io.accessArrayReq.bits.optype:= AccessArrayType.READ
    io.accessArrayReq.bits.addr:= 0.U
    val busy= (proc_state=/=proc_idle)
    io.busy:= busy

    // Trace 
    val trace_state_some = if (sysCfg.en_trace) Some(RegInit(0.U(io.rec_req.get.bits.rec_state.getWidth.W))) else None
    if(sysCfg.en_trace)
    {
        val traceClient_acc=new TraceClient(ClientName="ACC")
        val io_rec_req = io.rec_req.get
        val trace_state = trace_state_some.get
        io_rec_req.valid:= (proc_state===send_trace)
        io_rec_req.bits.rec_state:=trace_state
        io_rec_req.bits.picAddr:=base_src_picAddr_reg

        // Command part is custom
        traceClient_acc.addCustomField(field=arrayNum_reg,
                                     field_name="arrayNum")
        traceClient_acc.addCustomField(field=total_rowNum,
                                     field_name="total_rowNum")
        traceClient_acc.addCustomField(field=dest_picAddr_reg,
                                     field_name="dest_picADDR")
        traceClient_acc.addCustomField(field=load_dest_reg,
                                     field_name="if_load_dest")
        // 保持与上面一致
        io_rec_req.bits.command:=Cat(load_dest_reg,
                                    dest_picAddr_reg,
                                    total_rowNum,
                                    arrayNum_reg)
        // 加到cfg中
        sysCfg.traceCfg.helper.addTraceClient(traceClient_acc)

        when(proc_state===send_trace && io_rec_req.fire)
        {proc_state:=Mux(trace_state===RecStateType.START_RUN,Mux(load_dest_reg,load_dest,proc_read),proc_idle)}
    }

    switch(proc_state)
    {
        is(proc_idle)
        {   
            when(io.accumulate_req.fire)
            {
                arrayNum_reg:=io.accumulate_req.bits.src_arrayNum
                base_src_picAddr_reg:=io.accumulate_req.bits.base_src_picAddr
                dest_picAddr_reg:=io.accumulate_req.bits.dest_picAddr
                total_rowNum:=io.accumulate_req.bits.row_num
                accumulator_buf:=0.U
                row_ptr:=0.U
                read_op_array_ptr:=0.U
                load_dest_reg:=io.accumulate_req.bits.load_dest
                bitWidth_reg:=io.accumulate_req.bits.bitWidth

            
                // Triger trace rec !!!! Start
                if(sysCfg.en_trace)
                {   
                    val trace_state = trace_state_some.get
                    trace_state:=RecStateType.START_RUN
                    proc_state:=send_trace  
                }
                else
                {   proc_state :=Mux(io.accumulate_req.bits.load_dest,load_dest,proc_read)  }
            }

        }
        is(load_dest)
        {
            io.accessArrayReq.valid:=true.B
            when(io.accessArrayReq.fire)
            {
                io.accessArrayReq.bits.optype:=AccessArrayType.READ
                io.accessArrayReq.bits.addr:=dest_picAddr_reg+row_ptr
                proc_state := rev_dest
            }
        }
        is(rev_dest)
        {
            accumulator_buf:=io.dataReadFromBank
            proc_state := proc_read
        }
        is(proc_read)
        {   
            io.accessArrayReq.valid:= !finishRead
            // Acc_req_sent should next cycle receive data
            when(io.accessArrayReq.fire)
            {
                io.accessArrayReq.bits.optype:=AccessArrayType.READ
                io.accessArrayReq.bits.addr:=selected_array_row_addr
                // 指向下一个array，读下一个待加数(64b)
                read_op_array_ptr:=read_op_array_ptr+1.U
            }

            val is_read_op=RegNext(io.accessArrayReq.bits.optype)===AccessArrayType.READ
            when(RegNext(io.accessArrayReq.fire) & is_read_op)
            {accumulator_buf := Mux(bitWidth_reg===CalInfo.ACC_32BIT,add_32b(accumulator_buf,io.dataReadFromBank),
                                                                    add_16b(accumulator_buf,io.dataReadFromBank))}

            when(finishRead)
            {
                proc_state := proc_write_back
                read_op_array_ptr := 0.U
            }
        }
        is(proc_write_back)
        {
            io.accessArrayReq.valid:= true.B
            when(io.accessArrayReq.fire)
            {
                io.accessArrayReq.bits.optype:=AccessArrayType.WRITE
                io.accessArrayReq.bits.addr:=dest_picAddr_reg+row_ptr
                io.accessArrayReq.bits.dataWrittenToBank:=accumulator_buf

                accumulator_buf:=0.U
                row_ptr:=row_ptr+1.U

                if(sysCfg.en_trace)
                {
                    val trace_state = trace_state_some.get
                    trace_state:=RecStateType.END_RUN

                    proc_state:=Mux(row_ptr===(total_rowNum-1.U),
                                send_trace,
                                Mux(load_dest_reg,load_dest,proc_read))
                }
                else
                {
                    proc_state:=Mux(row_ptr===(total_rowNum-1.U),
                                proc_idle,
                                Mux(load_dest_reg,load_dest,proc_read))
                }

            }
        }
    }


    def add_32b(acc_reg:UInt,acc_data:UInt): UInt=
    {
        val lsb=acc_reg(31,0)+acc_data(31,0)
        val hsb=acc_reg(63,32)+acc_data(63,32)

        Cat(hsb,lsb)

    }

    def add_16b(acc_reg:UInt,acc_data:UInt): UInt=
    {
        val lsb0=acc_reg(15,0)+acc_data(15,0)
        val lsb1=acc_reg(31,16)+acc_data(31,16)
        val hsb0=acc_reg(47,32)+acc_data(47,32)
        val hsb1=acc_reg(63,48)+acc_data(63,48)

        Cat(hsb1,hsb0,lsb1,lsb0)
    }



    
}