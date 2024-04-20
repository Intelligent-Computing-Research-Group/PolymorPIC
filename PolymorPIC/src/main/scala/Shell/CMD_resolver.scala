package PIC

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.Buffer

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.regmapper._


class CmdQueue extends Bundle
{
  val funct=UInt(7.W)
  val rs1=UInt(64.W)
  val rs2=UInt(32.W)
}

class CMD_resolver(sysCfg:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends LazyModule 
{
  val addr_MMIO=sysCfg.cmd_enq_MMIO_bigInt
  val device = new SimpleDevice("reveice_cmd_node", Seq("from_cbus,receive_and_reslove_rocc_cmd"))
  println("Rocc send addr = ",addr_MMIO)
  val reveice_cmd_node = TLRegisterNode(
    address = Seq(AddressSet(addr_MMIO, 0xff)),
    device = device,
    beatBytes = 8,
    concurrency = 1)

  lazy val module = new CMD_resolver_impl
  class CMD_resolver_impl extends LazyModuleImp(this) 
  {
    val io=IO(new Bundle {

      // Send req to p2s_R
      val p2s_R_req=Decoupled(new P2S_R_req(sysCfg))
      val busy_p2s_R=Input(Bool())

      // Send req to p2s_L
      val p2s_L_req=Decoupled(new P2S_L_req(sysCfg))
      val busy_p2s_L=Input(Bool())

      // Send req to Load P
      val load_P_req=Decoupled(new Load_P_req(sysCfg))
      val busy_load_P=Input(Bool())

      // Send req to Store P
      val store_P_req=Decoupled(new Store_P_req(sysCfg))
      val busy_store_P=Input(Bool())

      // Send req to accumulator
      val accumulate_req= Decoupled(new Accumu_req(sysCfg,core_configs))
      val accumulate_busy= Input(Bool())

      // Send req to set_up
      val set_up_addr=Output(UInt(log2Ceil(sysCfg.total_valid_mats).W))
      val set_up_io=Flipped(new SetUpIO(core_configs))

      // Send and receive switch req and resp
      val switch_req=Decoupled(new SwitchInfo(sysCfg))
      val switch_resp=Flipped(Decoupled(new SwitchResult(sysCfg)))

      // state query
      val query_mat_req=Decoupled(new QueryMatReq(sysCfg))
      val mat_busy=Input(Bool())

      // trace
      val save_trace_req= if(sysCfg.en_trace) Some(Decoupled(new RecSaveReq(sysCfg))) else None
      val save_busy= if(sysCfg.en_trace) Some(Input(Bool())) else None

    })
    val isa_pmt_str = new StringBuilder()

    // Init
    io.store_P_req.valid:=false.B
    io.p2s_R_req.valid:=false.B
    io.p2s_L_req.valid:=false.B
    io.load_P_req.valid:=false.B
    io.accumulate_req.valid:=false.B
    io.switch_req.valid:=false.B
    io.switch_resp.ready:=true.B
    io.query_mat_req.valid:=false.B
    io.set_up_io.init()

    val (rocc_tl, rocc_edge) = reveice_cmd_node.in(0)
    val cmd_queue = Module(new Queue(new CmdQueue, 16))
    val immediate_cmd = RegInit(0.U(64.W))
    reveice_cmd_node.regmap(
        0x00 -> Seq(RegField(64,immediate_cmd)),
    )
    val mm_addr=sysCfg.cmd_enq_MMIO
    val mm_addr_queue=mm_addr+0.U
    cmd_queue.io.enq.valid:=false.B
    cmd_queue.io.deq.ready:=false.B

    rocc_tl.a.ready:=false.B
    val proc_idle ::fire_cmd :: load_P :: p2s_R :: p2s_L :: depency_check::store_P :: accumulate :: exe :: switch_req:: switch_resp:: save_trace ::wait_finish :: Nil = Enum(13)
    val cmd_proc_state = RegInit(proc_idle)

    val rev_idle :: return_by_D  ::en_queue::Nil = Enum(3)
    val cmd_rev_state = RegInit(rev_idle)

    // Cmd enq storage
    val rs1_enq_reg=RegInit(0.U(64.W))
    val rs2_enq_reg=RegInit(0.U(32.W))
    val funct_enq_reg=RegInit(0.U(7.W))
    // Cmd deq storage
    val rs2_deq_reg=RegInit(0.U(32.W))
    val funct_deq_reg=RegInit(0.U(7.W))
    val rs1_deq_reg=RegInit(0.U(64.W))

    // switch reg
    val switch_op_success=RegInit(false.B)
    val switch_op_matID_begin=RegInit(0.U(log2Ceil(sysCfg.total_valid_mats).W))
    val switch_op_matID_end=RegInit(0.U(log2Ceil(sysCfg.total_valid_mats).W))


    val first_cmd=RegInit(true.B)

    val query_addr_rev=RegInit(0.U(6.W))

    val d_channel_return_val=RegInit(0.U(64.W))
    rocc_tl.d.bits.data := d_channel_return_val

    cmd_queue.io.enq.bits.funct:=0.U
    cmd_queue.io.enq.bits.rs1:=0.U
    cmd_queue.io.enq.bits.rs2:=0.U

    val is_queue_full= !cmd_queue.io.enq.ready
    val is_queue_empty= !cmd_queue.io.deq.valid
    val is_cmd_proc_free= (cmd_proc_state===proc_idle)

    // check state TODO 这里可以只保存mat的ID，不用full address
    val check_mat_ptr=RegInit(0.U((sysCfg.total_valid_mats+1).W))
    val check_mat_endID=RegInit(0.U(sysCfg.total_valid_mats.W))
    io.query_mat_req.bits.matID:=check_mat_ptr

    val _ISA_ = PolymorPIC_Configs._ISA_
    switch(cmd_rev_state)
    {
        is(rev_idle)
        {
          rocc_tl.a.ready:=true.B
          when(rocc_tl.a.fire)
          {
              when(first_cmd) // is lsb = funct+cmd
              {
                val rs2=rocc_tl.a.bits.data(31,0)
                val funct=rocc_tl.a.bits.data(38,32)
                val is_immed= (funct===_ISA_.QUERY)
                rs2_enq_reg   :=rs2
                funct_enq_reg :=funct
                // flags
                first_cmd:= Mux(is_immed,first_cmd,false.B)
                d_channel_return_val:=Mux(is_immed,Cat(switch_op_matID_end,switch_op_matID_begin,switch_op_success,is_queue_empty&&is_cmd_proc_free),d_channel_return_val) // 用于返回给软件
                cmd_rev_state:=rev_idle   // no matter whether the queue has space, lsb will be kept in reg
              }
              .otherwise  // hsb, ca en queue
              {
                val rs1=rocc_tl.a.bits.data
                rs1_enq_reg:=rs1
                d_channel_return_val:=Mux(is_queue_full,"hFFFFFFFF".U,0.U)  // 用于硬件判断 队满只要重发hsb
                cmd_rev_state:=Mux(is_queue_full,rev_idle,en_queue)
              }
          }
        }
        is(en_queue)
        {
            first_cmd:=true.B
            cmd_queue.io.enq.valid:=true.B
            when(cmd_queue.io.enq.fire)
            {
                cmd_queue.io.enq.bits.funct:=funct_enq_reg
                cmd_queue.io.enq.bits.rs1:=rs1_enq_reg
                cmd_queue.io.enq.bits.rs2:=rs2_enq_reg
                cmd_rev_state:=rev_idle
            }
        }
    }

    // 处理出队的指令
    send_load_P_req(load_P_req=io.load_P_req,rs2=rs2_deq_reg,rs1=rs1_deq_reg)
    send_store_P_req(store_P_req=io.store_P_req,rs2=rs2_deq_reg,rs1=rs1_deq_reg)
    send_accumulate_req(accReq=io.accumulate_req,rs2=rs2_deq_reg,rs1=rs1_deq_reg)
    send_p2s_R_req(p2s_R_req=io.p2s_R_req,rs2=rs2_deq_reg,rs1=rs1_deq_reg)
    send_p2s_L_req(p2s_L_req=io.p2s_L_req,rs2=rs2_deq_reg,rs1=rs1_deq_reg)
    send_exe_req(setupIO=io.set_up_io,setupAddr=io.set_up_addr,rs2=rs2_deq_reg,rs1=rs1_deq_reg)
    send_pic_switch_req(pic_switch_req=io.switch_req,rs2=rs2_deq_reg,rs1=rs1_deq_reg)
    

    // trace logic
    if(sysCfg.en_trace)
    {
      val io_save_trace_req = io.save_trace_req.get
      send_save_trace_req(save_trace_req=io_save_trace_req,rs2=rs2_deq_reg,rs1=rs1_deq_reg)
      io_save_trace_req.valid:= (cmd_proc_state===save_trace)
      when(cmd_proc_state===save_trace && io_save_trace_req.fire)
      {
          cmd_proc_state:=wait_finish
      }
    }
    switch(cmd_proc_state)
    {
        is(proc_idle) // 0
        {
            cmd_queue.io.deq.ready:=true.B
            when(cmd_queue.io.deq.fire)
            {
              val cmd_bundle=cmd_queue.io.deq.bits
              // kep住指令
              rs2_deq_reg   :=cmd_bundle.rs2
              rs1_deq_reg   :=cmd_bundle.rs1
              val funct=cmd_bundle.funct
              funct_deq_reg :=funct
              // 执行ACCUMULATE前要检查执行状态
              cmd_proc_state:=fire_cmd

            }
        }
        is(fire_cmd)
        {
            when(funct_deq_reg===_ISA_.LOAD)
            {
                val load_type=_ISA_.LOAD_info.getLoadType(rs1=rs1_deq_reg,rs2=rs2_deq_reg)
                printf("\n%d\n",load_type)
                cmd_proc_state:=Mux(load_type===_ISA_.LOAD_info.LOAD_P,
                                    load_P,
                                    Mux(load_type===_ISA_.LOAD_info.P2S_R,p2s_R,p2s_L))}
            .elsewhen(funct_deq_reg===_ISA_.EXE)
            { cmd_proc_state:=exe}
            .elsewhen(funct_deq_reg===_ISA_.PIC_SWITCH)
            { cmd_proc_state:=switch_req}
            .elsewhen(funct_deq_reg===_ISA_.STORE_P)
            { 
              val opISA=PolymorPIC_Configs._ISA_.STORE_P_info
              val basePIC_Addr_func=opISA.RS1.getField("basePIC_Addr")
              val row_func=opISA.RS2.getField("Parameters").getField("P_block_row")
              val _64b_per_row_func=opISA.RS1.getField("P_block_64b_per_row")
              val baseMatID=sysCfg.get_full_matID(basePIC_Addr_func.extractVal(rs1=rs1_deq_reg,rs2=rs2_deq_reg)(sysCfg.accessBankFullAddr_sigLen-1,0))
              val matNum=(row_func.extractVal(rs1=rs1_deq_reg,rs2=rs2_deq_reg)*_64b_per_row_func.extractVal(rs1=rs1_deq_reg,rs2=rs2_deq_reg))>>(sysCfg.core_config.core_addrLen)
              check_mat_ptr := baseMatID
              check_mat_endID:=baseMatID+matNum
              cmd_proc_state:=depency_check
            }
            .elsewhen(funct_deq_reg===_ISA_.ACC)
            {
              val opISA=PolymorPIC_Configs._ISA_.ACC_info
              val base_src_picAddr_func=opISA.RS1.getField("PIC_Addr_src")
              val src_arrayNum_func=opISA.RS2.getField("Parameters").getField("SrcNum")
              val baseMatID= sysCfg.get_full_matID(base_src_picAddr_func.extractVal(rs1=rs1_deq_reg,rs2=rs2_deq_reg)(sysCfg.accessBankFullAddr_sigLen-1,0))
              val matNum=src_arrayNum_func.extractVal(rs1=rs1_deq_reg,rs2=rs2_deq_reg)
              check_mat_ptr := baseMatID
              check_mat_endID := baseMatID+matNum
              cmd_proc_state:=depency_check
            }
            .elsewhen(funct_deq_reg===_ISA_.SAVE_TRACE)
            {
              cmd_proc_state:=save_trace
            }
        }
        is(depency_check)
        {
          val op_chioce=Mux(funct_deq_reg===_ISA_.STORE_P,store_P,accumulate)
          // io.state_query_matID:=check_mat_ptr
          when(check_mat_ptr<check_mat_endID)
          {
            io.query_mat_req.valid:=true.B
            when(io.query_mat_req.fire)
            {
              check_mat_ptr:=Mux(io.mat_busy,check_mat_ptr,
                                          check_mat_ptr+1.U)
            }
          }
          .otherwise
          {cmd_proc_state:=op_chioce}
        }
        // ---------------------------------------- THe following may not be update --------------------------------------------
        is(load_P)
        {
            io.load_P_req.valid:=true.B
            when(io.load_P_req.fire)
            {  cmd_proc_state:=wait_finish}
        }
        is(p2s_R)
        {
            io.p2s_R_req.valid:=true.B
            when(io.p2s_R_req.fire)
            {  cmd_proc_state:=wait_finish}
        }
        is(p2s_L)
        {
            io.p2s_L_req.valid:=true.B
            when(io.p2s_L_req.fire)
            {  cmd_proc_state:=wait_finish}
        }
        is(store_P)
        {
          io.store_P_req.valid:=true.B
          when(io.store_P_req.fire)
          { cmd_proc_state:=wait_finish  }
        }
        is(accumulate)
        {
          io.accumulate_req.valid:=true.B
          when(io.accumulate_req.fire)
          { cmd_proc_state:=wait_finish }
        }
        is(exe)
        {
          io.set_up_io.exec:=true.B
          cmd_proc_state:=proc_idle
        }
        is(switch_req)
        {
          io.switch_req.valid:=true.B
          when(io.switch_req.fire)
          { cmd_proc_state:=switch_resp}
        }
        is(switch_resp)
        {
          when(io.switch_resp.fire)
          {
            switch_op_success:=io.switch_resp.bits.op_success
            switch_op_matID_end:=(sysCfg.total_valid_mats-1).U
            switch_op_matID_begin:=io.switch_resp.bits.avail_MatID_begin
            cmd_proc_state:=proc_idle
          }
        }
        is(wait_finish)
        {
          val trace_busy = if (sysCfg.en_trace) io.save_busy.get else false.B
          val busy= io.busy_load_P || io.busy_p2s_R || io.busy_p2s_L || io.busy_store_P || io.accumulate_busy || trace_busy

          cmd_proc_state:=Mux(busy,cmd_proc_state,proc_idle)
        }
    }


    // Functions
    def send_load_P_req(load_P_req: DecoupledIO[Load_P_req],rs2:UInt,rs1: UInt) : Unit={
      val isaPairsSeq: Buffer[Seq[(String, Any)]] = Buffer()
      val opISA=PolymorPIC_Configs._ISA_.LOAD_info

      // baseAddr_DRAM
      val baseAddr_DRAM_func=opISA.RS1.getField("baseDRAM_Addr")
      load_P_req.bits.baseAddr_DRAM:=baseAddr_DRAM_func.extractVal(rs1=rs1,rs2=rs2)

      // baseAddr_PIC
      val baseAddr_PIC_func=opISA.RS1.getField("basePIC_Addr")
      val baseAddr_Array_sigLen=load_P_req.bits.baseAddr_Array.getWidth
      load_P_req.bits.baseAddr_Array:=baseAddr_PIC_func.extractVal(rs1=rs1,rs2=rs2)

      // len64_per_row
      val len64_per_row_funct=opISA.RS1.getField("LoadLen")
      val len64_per_row_sigLen=load_P_req.bits.len64_per_row.getWidth
      load_P_req.bits.len64_per_row:=len64_per_row_funct.extractVal(rs1=rs1,rs2=rs2)
      
      val params=opISA.RS2.getField("Parameters")
      // row
      val len_funct=params.getField("P_block_row")
      val len_sigLen=load_P_req.bits.row.getWidth
      load_P_req.bits.row:=len_funct.extractVal(rs1=rs1,rs2=rs2)(len_sigLen-1,0)

      // offset
      val offset=params.getField("Offset")
      load_P_req.bits.offset:=offset.extractVal(rs1=rs1,rs2=rs2)
    }


    def send_p2s_R_req(p2s_R_req: DecoupledIO[P2S_R_req],rs1:UInt,rs2:UInt) : Unit={
      val isaPairsSeq: Buffer[Seq[(String, Any)]] = Buffer()
      val opISA=PolymorPIC_Configs._ISA_.LOAD_info

      // baseAddr_DRAM
      val baseAddr_DRAM_func=opISA.RS1.getField("baseDRAM_Addr")
      p2s_R_req.bits.dramAddr:= baseAddr_DRAM_func.extractVal(rs1=rs1,rs2=rs2)

      // baseAddr_PIC need to extract arrayID TODO only need matID
      val baseAddr_PIC_func=opISA.RS1.getField("basePIC_Addr")
      p2s_R_req.bits.base_arrayID_to_store:=baseAddr_PIC_func.extractVal(rs1=rs1,rs2=rs2)(sysCfg.accessBankFullAddr_sigLen-1,log2Ceil(sysCfg.core_config.wordlineNums))

      // num_column
      val col_len_funct=opISA.RS1.getField("LoadLen")
      val num_column_sigLen=p2s_R_req.bits.num_column.getWidth
      p2s_R_req.bits.num_column := col_len_funct.extractVal(rs1=rs1,rs2=rs2)(num_column_sigLen-1,0)

      // -------------------params-------------------
      val params=opISA.RS2.getField("Parameters")
      // offset one elem
      val offset=params.getField("Offset")
      p2s_R_req.bits.next_row_offset_elem:=offset.extractVal(rs1=rs1,rs2=rs2)

      // nBuf
      val nBuf_sigLen=params.getField("nBuf")
      p2s_R_req.bits.bufNum:=nBuf_sigLen.extractVal(rs1=rs1,rs2=rs2)

      // nBit
      val nBit_sigLen=params.getField("nBit")
      p2s_R_req.bits.precision:=nBit_sigLen.extractVal(rs1=rs1,rs2=rs2)

      // flags
      // add_info(isaPairsSeq,opISA.Params.split)
      // add_info(isaPairsSeq,opISA.Params.transpose)

    }

    def send_p2s_L_req(p2s_L_req: DecoupledIO[P2S_L_req],rs1:UInt,rs2:UInt) : Unit={
      val isaPairsSeq: Buffer[Seq[(String, Any)]] = Buffer()
      val opISA=PolymorPIC_Configs._ISA_.LOAD_info

      // dramAddr
      val baseAddr_DRAM_func=opISA.RS1.getField("baseDRAM_Addr")
      p2s_L_req.bits.base_dramAddr_to_load:= baseAddr_DRAM_func.extractVal(rs1=rs1,rs2=rs2)

      // baseAddr_PIC
      val baseAddr_PIC_func=opISA.RS1.getField("basePIC_Addr")
      p2s_L_req.bits.base_picAddr_to_store:=baseAddr_PIC_func.extractVal(rs1=rs1,rs2=rs2)

      // _L_block_row
      val _L_block_row_func=opISA.RS1.getField("LoadLen")
      p2s_L_req.bits._L_block_row :=_L_block_row_func.extractVal(rs1=rs1,rs2=rs2)

      // -------------------params-------------------
      val params=opISA.RS2.getField("Parameters")
      // offset one elem
      val offset=params.getField("Offset")
      p2s_L_req.bits.next_row_offset_elem:=offset.extractVal(rs1=rs1,rs2=rs2)

      // nBit
      val precision_func=params.getField("nBit")
      p2s_L_req.bits.precision:=precision_func.extractVal(rs1=rs1,rs2=rs2)

      // flags
      // add_info(isaPairsSeq,opISA.Params.split)
      // add_info(isaPairsSeq,opISA.Params.transpose)
    }

    def send_store_P_req(store_P_req: DecoupledIO[Store_P_req],rs1:UInt,rs2:UInt) : Unit={
      val isaPairsSeq: Buffer[Seq[(String, Any)]] = Buffer()
      val opISA=PolymorPIC_Configs._ISA_.STORE_P_info

      // baseAddr_DRAM
      val baseAddr_DRAM_func=opISA.RS1.getField("baseDRAM_Addr")
      store_P_req.bits.baseAddr_DRAM:=baseAddr_DRAM_func.extractVal(rs1=rs1,rs2=rs2)

      // basePIC_Addr
      val basePIC_Addr_func=opISA.RS1.getField("basePIC_Addr")
      store_P_req.bits.baseAddr_Array:=basePIC_Addr_func.extractVal(rs1=rs1,rs2=rs2)

      // P_block_64b_per_row
      val _64b_per_row_func=opISA.RS1.getField("P_block_64b_per_row")
      store_P_req.bits.len64_per_row:=_64b_per_row_func.extractVal(rs1=rs1,rs2=rs2)

      // -------------------params-------------------
      val params=opISA.RS2.getField("Parameters")
      // offset one elem
      val offset=params.getField("Offset")
      store_P_req.bits.offset:=offset.extractVal(rs1=rs1,rs2=rs2)

      val row_funct=params.getField("P_block_row")
      val row_sigLen=store_P_req.bits.row.getWidth
      store_P_req.bits.row:=row_funct.extractVal(rs1=rs1,rs2=rs2)(row_sigLen-1,0)

    }

    def send_accumulate_req(accReq:DecoupledIO[Accumu_req],rs1:UInt,rs2:UInt) :Unit ={
      val isaPairsSeq: Buffer[Seq[(String, Any)]] = Buffer()
      val opISA=PolymorPIC_Configs._ISA_.ACC_info

      // src addr
      val base_src_picAddr_func=opISA.RS1.getField("PIC_Addr_src")
      accReq.bits.base_src_picAddr:=base_src_picAddr_func.extractVal(rs1=rs1,rs2=rs2)

      // dest addr
      val dest_picAddr_func=opISA.RS1.getField("PIC_Addr_dest")
      accReq.bits.dest_picAddr:=dest_picAddr_func.extractVal(rs1=rs1,rs2=rs2)

      // len
      val row_num_func=opISA.RS1.getField("AccLen")
      accReq.bits.row_num:=row_num_func.extractVal(rs1=rs1,rs2=rs2)

      // -------------------params-------------------
      val params=opISA.RS2.getField("Parameters")
      // src num
      val src_arrayNum_func=params.getField("SrcNum")
      accReq.bits.src_arrayNum:=src_arrayNum_func.extractVal(rs1=rs1,rs2=rs2)

      accReq.bits.load_dest:=params.getField("LoadDest").extractVal(rs1=rs1,rs2=rs2)

      accReq.bits.bitWidth:=params.getField("BitWidth").extractVal(rs1=rs1,rs2=rs2)
      
    }

    def send_exe_req(setupIO: SetUpIO,setupAddr:UInt,rs1:UInt,rs2:UInt): Unit = {
      val isaPairsSeq: Buffer[Seq[(String, Any)]] = Buffer()
      val opISA=PolymorPIC_Configs._ISA_.EXE_info

      // setupAddr
      val setupAddr_func=opISA.RS1.getField("PIC_Addr_R")
      val matID=setupAddr_func.extractVal(rs1=rs1,rs2=rs2)
      // assert(matID(setupAddr_func.len-1,setupAddr.getWidth)===0.U,"You may give a full addr, not a specified matID! This will be a catastrophe!")
      setupAddr:=setupAddr_func.extractVal(rs1=rs1,rs2=rs2)(setupAddr.getWidth-1,0)

      // L addr
      val _L_Addr_sig=opISA.RS1.getField("PIC_Addr_L")
      setupIO._L_vec_fetch_addr:=_L_Addr_sig.extractVal(rs1=rs1,rs2=rs2)

      // R_Valid_nCols
      val _R_block_row_sig=opISA.RS1.getField("R_Valid_nCols")
      setupIO._R_block_row:=_R_block_row_sig.extractVal(rs1=rs1,rs2=rs2)

      // ---------------- Parameters ------------------
      val params=opISA.RS2.getField("Parameters")
      // BufNum
      setupIO.nBuf:=params.getField("nBuf").extractVal(rs1=rs1,rs2=rs2)
      
      // CalNum
      setupIO.nCal:=params.getField("nCal").extractVal(rs1=rs1,rs2=rs2)

      // Base_R_Bit
      setupIO._R_base_bit:=params.getField("Base_R_Bit").extractVal(rs1=rs1,rs2=rs2)
      
      // L_Precision
      setupIO._L_precision:=params.getField("L_Precision").extractVal(rs1=rs1,rs2=rs2)

      // L_Block_Row
      setupIO._L_block_row:=params.getField("L_Block_Row").extractVal(rs1=rs1,rs2=rs2)

      // Sign
      setupIO.if_signed:=params.getField("Sign").extractVal(rs1=rs1,rs2=rs2)

      // BitWidth
      setupIO.accWidth:=params.getField("BitWidth").extractVal(rs1=rs1,rs2=rs2)

    }

    def send_pic_switch_req(pic_switch_req: DecoupledIO[SwitchInfo],rs1:UInt,rs2:UInt) : Unit={
      val isaPairsSeq: Buffer[Seq[(String, Any)]] = Buffer()
      val opISA=PolymorPIC_Configs._ISA_.PIC_SWITCH_info

      pic_switch_req.bits.op:=opISA.RS1.getField("opType").extractVal(rs1=rs1,rs2=rs2)
      pic_switch_req.bits.nLevels:=opISA.RS1.getField("nLevels").extractVal(rs1=rs1,rs2=rs2)
    }

    def send_save_trace_req(save_trace_req: DecoupledIO[RecSaveReq],rs1:UInt,rs2:UInt) : Unit={
      val opISA=PolymorPIC_Configs._ISA_.SAVE_TRACE_info

      save_trace_req.bits.pic_addr:=opISA.RS1.getField("pic_dest_addr").extractVal(rs1=rs1,rs2=rs2)
    }

  }
}


