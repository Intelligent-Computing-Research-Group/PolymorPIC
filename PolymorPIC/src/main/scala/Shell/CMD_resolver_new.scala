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

class AllCmdQueue extends Bundle
{
  val funct=UInt(7.W)
  val rs1=UInt(64.W)
  val rs2=UInt(32.W)
}

class SubCmdQueue extends Bundle
{
  val rs1=UInt(64.W)
  val rs2=UInt(32.W)
}

class CMD_resolver_new(sysCfg:Sys_Config,coreCfg:PolymorPIC_Kernal_Config)(implicit p: Parameters) extends LazyModule 
{
  val addr_MMIO=sysCfg.cmd_enq_MMIO_bigInt
  val device = new SimpleDevice("reveice_cmd_node", Seq("from_cbus,receive_and_reslove_rocc_cmd"))
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
      val accumulate_req= Decoupled(new Accumu_req(sysCfg,coreCfg))
      val busy_acc= Input(Bool())

      // Send req to set_up
      val set_up_addr=Output(UInt(log2Ceil(sysCfg.total_valid_mats).W))
      val set_up_io=Flipped(new SetUpIO(coreCfg))

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
    
    // TL
    val (rocc_tl, rocc_edge) = reveice_cmd_node.in(0)
    val immediate_cmd = RegInit(0.U(64.W))
    reveice_cmd_node.regmap(
        0x00 -> Seq(RegField(64,immediate_cmd)),
    )
    val d_channel_return_val=RegInit(0.U(64.W))
    rocc_tl.d.bits.data := d_channel_return_val

    val rs1_enq_reg=RegInit(0.U(64.W))
    val rs2_enq_reg=RegInit(0.U(32.W))
    val funct_enq_reg=RegInit(0.U(7.W))
    // special command's reg
    val rs1_reg_special=RegInit(0.U(64.W))

    val first_cmd=RegInit(true.B)
    val rev_idle ::  enq_stage :: Nil = Enum(2)
    val cmd_rev_state = RegInit(rev_idle)
    rocc_tl.a.ready:= (cmd_rev_state===rev_idle)

    // For execute save trace and switch and init
    val save_trace_idle :: save_trace_req :: save_trace_resp :: Nil = Enum(3)
    val save_trace_state = RegInit(save_trace_idle)
    val save_trace_free = (save_trace_state===save_trace_idle)

    val switch_idle :: switch_req :: switch_resp :: Nil = Enum(3)
    val switch_state = RegInit(switch_idle)
    val switch_free = (switch_state===switch_idle)

    val init_idle :: init_table_req :: init_table_resp :: Nil = Enum(3)
    val init_state = RegInit(init_idle)
    val init_free=(init_state===init_idle)

    // Global table
    val Mat_State_Table=Module(new MatStateTable(sysCfg,coreCfg))

    // main queue
    val queue_Main = Module(new Queue(new AllCmdQueue, 4))
    val is_main_queue_full = !queue_Main.io.enq.ready
    val main_queue_has_val=queue_Main.io.deq.valid
    val main_queue_deq_bits=queue_Main.io.deq.bits
    val main_queue_deq_rs1=main_queue_deq_bits.rs1
    val main_queue_deq_rs2=main_queue_deq_bits.rs2
    val main_queue_deq_funct=main_queue_deq_bits.funct
    
    queue_Main.io.enq.valid:= (cmd_rev_state===enq_stage)
    queue_Main.io.enq.bits.funct:=funct_enq_reg
    queue_Main.io.enq.bits.rs1:=rs1_enq_reg
    queue_Main.io.enq.bits.rs2:=rs2_enq_reg

    // Gen ISA handler
    // ISA
    val ISA = PolymorPIC_Configs._ISA_
    val LOAD_INFO=ISA.LOAD_info
    val EXE_INFO=ISA.EXE_info
    val ST_P_INFO=ISA.STORE_P_info
    val ACC_INFO=ISA.ACC_info

    // Each mat's state
    val mat_busy_rec_vec=RegInit(VecInit(Seq.fill(sysCfg.total_valid_mats){false.B}))
    val all_mat_free = mat_busy_rec_vec.forall(_ === false.B)


    // SubQueue Part >>>>>>>>>>>>>>>>>>>>>>>>>>>>>
    val load_type = ISA.LOAD_info.getLoadType(rs1=main_queue_deq_rs1,rs2=main_queue_deq_rs2)

    val queue_Load_L = Module(new Queue(new SubCmdQueue, 6))
    val is_LD_L_cmd= (main_queue_deq_funct===ISA.LOAD&main_queue_has_val)&(load_type===ISA.LOAD_info.P2S_L)
    val queue_Load_L_has_val=queue_Load_L.io.deq.valid
    queue_Load_L.io.enq.valid:= is_LD_L_cmd

    val queue_Load_R = Module(new Queue(new SubCmdQueue, 8))
    val is_LD_R_cmd= (main_queue_deq_funct===ISA.LOAD&main_queue_has_val)&(load_type===ISA.LOAD_info.P2S_R)
    val queue_Load_R_has_val=queue_Load_R.io.deq.valid
    queue_Load_R.io.enq.valid:= is_LD_R_cmd

    val queue_Load_P = Module(new Queue(new SubCmdQueue, 4))
    val is_LD_P_cmd= (main_queue_deq_funct===ISA.LOAD&main_queue_has_val)&(load_type===ISA.LOAD_info.LOAD_P)
    val queue_Load_P_has_val = queue_Load_P.io.deq.valid
    queue_Load_P.io.enq.valid:= is_LD_P_cmd

    val queue_EXE = Module(new Queue(new SubCmdQueue, 16))
    val queue_EXE_has_val=queue_EXE.io.deq.valid
    queue_EXE.io.enq.valid:= (main_queue_has_val & main_queue_deq_funct===ISA.EXE)

    val queue_STORE_P = Module(new Queue(new SubCmdQueue, 4))
    val queue_STORE_P_has_val=queue_STORE_P.io.deq.valid
    queue_STORE_P.io.enq.valid:= (main_queue_has_val & main_queue_deq_funct===ISA.STORE_P)

    val queue_ACC = Module(new Queue(new SubCmdQueue, 4))
    val queue_ACC_has_val=queue_ACC.io.deq.valid
    queue_ACC.io.enq.valid:= (main_queue_has_val & main_queue_deq_funct===ISA.ACC)

    queue_Main.io.deq.ready:= queue_Load_L.io.enq.fire||queue_Load_R.io.enq.fire||queue_Load_P.io.enq.fire||
                              queue_EXE.io.enq.fire||queue_ACC.io.enq.fire||queue_STORE_P.io.enq.fire
    // <<<<<<<<<<<<<<<<<<<<< SubQueue Part 

    enq_sub_queue(queue_Load_L,main_queue_deq_rs1,main_queue_deq_rs2)
    enq_sub_queue(queue_Load_R,main_queue_deq_rs1,main_queue_deq_rs2)
    enq_sub_queue(queue_Load_P,main_queue_deq_rs1,main_queue_deq_rs2)
    enq_sub_queue(queue_EXE,main_queue_deq_rs1,main_queue_deq_rs2)
    enq_sub_queue(queue_STORE_P,main_queue_deq_rs1,main_queue_deq_rs2)
    enq_sub_queue(queue_ACC,main_queue_deq_rs1,main_queue_deq_rs2)

    val all_queue_free=  (!queue_Load_L_has_val)&
                          (!queue_Load_R_has_val)&
                          (!queue_Load_P_has_val)&
                          (!queue_EXE_has_val)&
                          (!queue_ACC_has_val)&
                          (!queue_STORE_P_has_val)&
                          (!main_queue_has_val)

    val no_exe_executing= all_mat_free

    val no_normal_cmd_executing= (!io.busy_p2s_R)&(!io.busy_p2s_L)&(!io.busy_acc)&(!io.busy_store_P)
    val normal_cmd_sche_free=Wire(Bool())
    val all_sche_free= save_trace_free&switch_free&init_free&normal_cmd_sche_free


    val total_free= all_queue_free&no_exe_executing&no_normal_cmd_executing&all_sche_free
    
    val enq_happen= (queue_Load_L.io.enq.fire)||
                    (queue_Load_R.io.enq.fire)||
                    (queue_Load_P.io.enq.fire)||
                    (queue_EXE.io.enq.fire)||
                    (queue_STORE_P.io.enq.fire)||
                    (queue_ACC.io.enq.fire)

    // switch
    val switch_op_success=RegInit(false.B)
    val switch_op_matID_begin=RegInit(0.U(log2Ceil(sysCfg.total_valid_mats).W))
    val switch_op_matID_end=RegInit(0.U(log2Ceil(sysCfg.total_valid_mats).W))
    send_pic_switch_req(pic_switch_req=io.switch_req,rs2=rs2_enq_reg,rs1=rs1_reg_special) // rs2用不到所以无所谓
    io.switch_req.valid:=(switch_state===switch_req)&all_queue_free&no_exe_executing
    io.switch_resp.ready:=(switch_state===switch_resp)

    // immediate return command : like query
    val immed_return_info= Cat(switch_op_matID_end,
                              switch_op_matID_begin,
                              switch_op_success,
                              total_free)

    // init table 
    val INIT_TAB_query_port=Mat_State_Table.io.query_req(QryTabClient.INIT)
    INIT_TAB_query_port.valid:=(init_state===init_table_req)&all_queue_free&no_exe_executing
    INIT_TAB_query_port.bits.begin_matID:=sysCfg.first_matID.U
    INIT_TAB_query_port.bits.end_matID:=sysCfg.last_matID.U
    INIT_TAB_query_port.bits.extra_bufID:=DontCare
    INIT_TAB_query_port.bits.state:=DontCare
    val init_flags_wire=WireInit(false.B)

    // 如过有save trace请求，那么设置个为true
    val trace_assert_st_P=RegInit(false.B)


    switch(cmd_rev_state)
    {
        is(rev_idle)
        {
          when(rocc_tl.a.fire)
          {
              when(first_cmd) // is lsb = funct+cmd
              {
                val rs2=rocc_tl.a.bits.data(31,0)
                val funct=rocc_tl.a.bits.data(38,32)
                val is_immed_cmd= (funct===ISA.QUERY)
                rs2_enq_reg   :=rs2
                funct_enq_reg :=funct
                // flags
                first_cmd:= Mux(is_immed_cmd,first_cmd,false.B)
                d_channel_return_val:=Mux(is_immed_cmd,immed_return_info,d_channel_return_val)
                cmd_rev_state:=rev_idle   // no matter whether the queue has space, lsb will be kept in reg
              }
              .otherwise  // hsb, ca en queue
              {
                // 如果is_main_queue_full或者save trace或者switch或者init在执行，那就必须返回重发
                val is_special= (funct_enq_reg===ISA.SAVE_TRACE)||(funct_enq_reg===ISA.INIT)||(funct_enq_reg===ISA.PIC_SWITCH)
                // 需要重发
                // 一般指令：有init，switch，save trace在执行，或者队满就重发
                // init等：
                val need_re_send_normal = is_main_queue_full|| (!init_free) || (!switch_free) || (!save_trace_free)
                val need_re_send_special = !total_free
                val need_re_send = (is_special&need_re_send_special)||(!is_special&need_re_send_normal)

                val rs1=rocc_tl.a.bits.data
                rs1_enq_reg:=rs1
                // If queue full, abandon this command
                d_channel_return_val:=Mux(need_re_send,"hFFFFFFFF".U,0.U) 
                cmd_rev_state:=Mux(need_re_send,rev_idle,
                                        Mux(is_special,rev_idle,enq_stage))

                // restore first command flag
                first_cmd:=Mux(need_re_send,false.B,true.B)
                
                // activate save trace或者switch或者init
                val can_fire_special=is_special&(!need_re_send_special)
                dontTouch(can_fire_special)
                rs1_reg_special:=Mux(can_fire_special,rs1,rs1_reg_special)
                init_state:=Mux(can_fire_special&funct_enq_reg===ISA.INIT,init_table_req,init_state)
                switch_state:=Mux(can_fire_special&funct_enq_reg===ISA.PIC_SWITCH,switch_req,switch_state)
                save_trace_state:=Mux(can_fire_special&funct_enq_reg===ISA.SAVE_TRACE,save_trace_req,save_trace_state)
              }
          }
        }
        is(enq_stage)
        {
          when(queue_Main.io.enq.fire)
          {cmd_rev_state:=rev_idle}
        }
    }

    // Global flags
    // stall counter     !!!! K从1开始的
    val stall_counter=RegInit(0.U(12.W))
    stall_counter:=stall_counter+1.U
    // L addr
    val LOAD_L_DONE_K=RegInit(0.U(10.W))
    val LOAD_L_DONE_addr=RegInit((sysCfg.total_addr-1).U(sysCfg.accessBankFullAddr_sigLen.W))
    
    val EXE_L_CUR_K=RegInit(1.U(10.W))    // This is running/finished record
    val EXE_L_CUR_addr=RegInit(0.U(10.W))

    // P addr
    val STORE_P_DONE_addr=RegInit(0.U(32.W))  // update by store,checked by load P
    val STORE_P_DONE_K=RegInit(0.U(10.W))  // update by store,checked by load P


    // ********************** Execute init ************************
    switch(init_state)
    {
      is(init_table_req)
      {
        when(INIT_TAB_query_port.fire)
        {
          init_flags_wire:=true.B
          init_state:=init_table_resp
        }
      }
      is(init_table_resp)
      {
        when(Mat_State_Table.io.resultEn)
        {
          first_cmd:=true.B
          init_state:=init_idle
        }
      }
    }

    when(init_flags_wire)
    {
      LOAD_L_DONE_K:=0.U
      LOAD_L_DONE_addr:=(sysCfg.total_addr-1).U(sysCfg.accessBankFullAddr_sigLen.W)
      EXE_L_CUR_K:=1.U
      EXE_L_CUR_addr:=0.U
      STORE_P_DONE_addr:=0.U
      STORE_P_DONE_K:=0.U
    }

    // ********************** Execute mode Switch ************************
    // switch
    switch(switch_state)
    {
      is(switch_req)
      {
        when(io.switch_req.fire)
        { cmd_rev_state:=switch_resp}
      }
      is(switch_resp)
      {
        when(io.switch_resp.fire)
        {
          first_cmd:=true.B
          switch_op_success:=io.switch_resp.bits.op_success
          switch_op_matID_end:=(sysCfg.total_valid_mats-1).U
          switch_op_matID_begin:=io.switch_resp.bits.avail_MatID_begin
          switch_state:=switch_idle
        }
      }
    }

    // ********************** Execute Save Trace ************************
    if(sysCfg.en_trace)
    {
      val io_save_trace_req = io.save_trace_req.get
      send_save_trace_req(save_trace_req=io_save_trace_req,rs2=rs2_enq_reg,rs1=rs1_reg_special)
      io_save_trace_req.valid:= (save_trace_state===save_trace_req)
      // Send req
      when(save_trace_state===save_trace_req && io_save_trace_req.fire)
      {
          save_trace_state:=save_trace_resp
          trace_assert_st_P:=true.B   // activate st p directly
      }

      // Wait finish
      when(save_trace_state===save_trace_resp)
      {
        val trace_busy = io.save_busy.get 
        save_trace_state:=Mux(trace_busy,save_trace_idle,save_trace_state)
      }
    }


    // ********************** Execute LOAD_L ************************
    val idle_LD_L :: check_LD_L :: fire_LD_L :: wait_LD_L_FINISH :: Nil = Enum(4)
    val LOAD_L_STATE = RegInit(idle_LD_L)
    val LOAD_L_deq_reg = RegInit(0.U.asTypeOf(new SubCmdQueue))
    val LOAD_L_addr=LOAD_INFO.RS1.getField("basePIC_Addr")
                              .extractVal(rs1=LOAD_L_deq_reg.rs1,rs2=LOAD_L_deq_reg.rs2)
    dontTouch(LOAD_L_addr)
    val LOAD_L_updated_K=Mux(LOAD_L_DONE_addr>=LOAD_L_addr,LOAD_L_DONE_K+1.U,LOAD_L_DONE_K) // 其实不存在等于的情况
    send_p2s_L_req(p2s_L_req=io.p2s_L_req,rs2=LOAD_L_deq_reg.rs2,rs1=LOAD_L_deq_reg.rs1)
    io.p2s_L_req.valid := (!io.busy_p2s_L)&(LOAD_L_STATE===fire_LD_L)

    queue_Load_L.io.deq.ready:=(LOAD_L_STATE===idle_LD_L)

    switch(LOAD_L_STATE)
    {
      is(idle_LD_L)
      {
        when(queue_Load_L.io.deq.fire)
        {
          LOAD_L_deq_reg:=queue_Load_L.io.deq.bits
          LOAD_L_STATE:=check_LD_L
        }
      }
      is(check_LD_L)
      {
        // Constrain
        // 检查 LoadL之前的K是否已经全部被算完并且可以覆盖，通过查看exe的L地址指针来看并且是要小于exe的，等于也不行
        // If exe k===updated_K can load
        // If exe k < updated_K , check if the LOAD_L_addr must lower than exe's
        // If ex k > updated_K can load, is impossible. exe执行前会检查P是否加载完
        // val same_K_with_exe = (LOAD_L_updated_K===EXE_L_CUR_K)
        // val can_load_L = Mux(same_K_with_exe,true.B,
        //                           Mux(LOAD_L_addr<EXE_L_CUR_addr,true.B,false.B))
        // assert(LOAD_L_updated_K>=EXE_L_CUR_K,"Error schedule!")

        // LOAD_L_STATE:=Mux(can_load_L,fire_LD_L,check_LD_L)
        LOAD_L_STATE:=fire_LD_L
      }
      is(fire_LD_L)
      {
        when(io.p2s_L_req.fire)
        {
          LOAD_L_STATE:=wait_LD_L_FINISH
        }
      }
      is(wait_LD_L_FINISH)
      {
        // Constrain
        // 更新L的全局指针,表示已经被load的L
        when(!io.busy_p2s_L)
        {
          // Constrain 1
          LOAD_L_DONE_K:=Mux(LOAD_L_addr<LOAD_L_DONE_addr,LOAD_L_DONE_K+1.U,LOAD_L_DONE_K)
          LOAD_L_DONE_addr:= LOAD_L_addr
          LOAD_L_STATE:=idle_LD_L
        }
      }
    }


    // ********************** Execute LOAD_R ************************
    val idle_LD_R :: query_tab_LD_R :: wait_query_LD_R :: stall_LD_R :: fire_LD_R  :: update_tab_LD_R :: Nil = Enum(6)
    val LOAD_R_STATE = RegInit(idle_LD_R)
    val LOAD_R_deq_reg = RegInit(0.U.asTypeOf(new SubCmdQueue))
    
    val LOAD_R_matID=sysCfg.get_full_matID(
                    LOAD_INFO.RS1.getField("basePIC_Addr")
                    .extractVal(rs1=LOAD_R_deq_reg.rs1,rs2=LOAD_R_deq_reg.rs2)(sysCfg.accessBankFullAddr_sigLen-1,0)
                    )
    val LOAD_R_nbit=Cat(0.U(1.W),LOAD_INFO.RS2.getField("Parameters").getField("nBit")
                            .extractVal(rs1=LOAD_R_deq_reg.rs1,rs2=LOAD_R_deq_reg.rs2))
    val LOAD_R_nMAC=4.U-LOAD_INFO.RS2.getField("Parameters").getField("nBuf")
                    .extractVal(rs1=LOAD_R_deq_reg.rs1,rs2=LOAD_R_deq_reg.rs2)
    val LOAD_R_matNum=Mux((LOAD_R_nbit+1.U)%(LOAD_R_nMAC)===0.U,(LOAD_R_nbit+1.U)/(LOAD_R_nMAC),(LOAD_R_nbit+1.U)/(LOAD_R_nMAC)+1.U)

    send_p2s_R_req(p2s_R_req=io.p2s_R_req,rs2=LOAD_R_deq_reg.rs2,rs1=LOAD_R_deq_reg.rs1)
    io.p2s_R_req.valid:= (!io.busy_p2s_R)&(LOAD_R_STATE===fire_LD_R)
    
    val LD_R_qry_port=Mat_State_Table.io.query_req(QryTabClient.LD_R)
    LD_R_qry_port.valid:=false.B
    LD_R_qry_port.bits.begin_matID:=LOAD_R_matID
    dontTouch(LOAD_R_matID)
    LD_R_qry_port.bits.end_matID:=LOAD_R_matID+LOAD_R_matNum-1.U
    LD_R_qry_port.bits.extra_bufID:=DontCare
    LD_R_qry_port.bits.state:=0.U

    queue_Load_R.io.deq.ready:=(LOAD_R_STATE===idle_LD_R)
    
    switch(LOAD_R_STATE)
    {
      is(idle_LD_R)
      {
        queue_Load_R.io.deq.ready:=true.B
        when(queue_Load_R.io.deq.fire)
        {
          LOAD_R_deq_reg:=queue_Load_R.io.deq.bits
          LOAD_R_STATE:=query_tab_LD_R
        }
      }
      is(query_tab_LD_R)
      {
        // Constrain
        // Load R 执行前检查上一轮计算的Store完成没
        // ----若完成，store会把LOAD R的flag设为INVALID，所以检查LD_R的flag是不是invalid
        // ----一开始前面无任何计算任务时候默认是INVAILID，所以可以运行
        LD_R_qry_port.valid:=true.B
        when(LD_R_qry_port.fire)
        {
          LD_R_qry_port.bits.state := OpStates.BEFORE_RUN
          LOAD_R_STATE :=wait_query_LD_R
        }
      }
      is(wait_query_LD_R)
      {
        when(Mat_State_Table.io.resultEn)
        {
          LOAD_R_STATE:=Mux(Mat_State_Table.io.result,fire_LD_R,stall_LD_R)
        }
      }
      is(stall_LD_R)
      {
        LOAD_R_STATE:=Mux((stall_counter&sysCfg.LD_R_stall_tick_mask)===0.U,
                            query_tab_LD_R,LOAD_R_STATE)
      }
      is(fire_LD_R)
      {
        when(io.p2s_R_req.fire)
        {
          LOAD_R_STATE:=update_tab_LD_R
        }
      }
      is(update_tab_LD_R)
      {
        // Constrain
        // Load R将对应Cmat的LoadR flag设置成Vliad,表示Load R完成了，后续计算可以开始(EXE会检查)
        LD_R_qry_port.valid:= (!io.busy_p2s_R)
        when(LD_R_qry_port.fire)
        {
          LD_R_qry_port.bits.state := OpStates.AFTER_RUN
          LOAD_R_STATE := idle_LD_R
        }
      }
    }
      // **********************************************************

      // ********************** Execute EXE ************************
      val idle_EXE :: check_LD_L_by_EXE :: query_tab_EXE  :: wait_query_EXE :: fire_EXE :: stall_EXE :: Nil = Enum(6)
      val EXE_STATE = RegInit(idle_EXE)
      val EXE_deq_reg = RegInit(0.U.asTypeOf(new SubCmdQueue))
    
      val EXE_L_addr=EXE_INFO.RS1.getField("PIC_Addr_L")
                              .extractVal(rs1=EXE_deq_reg.rs1,rs2=EXE_deq_reg.rs2)
      val EXE_mat_addr=EXE_INFO.RS1.getField("PIC_Addr_R")
                              .extractVal(rs1=EXE_deq_reg.rs1,rs2=EXE_deq_reg.rs2)
      val EXE_updated_K=Mux(EXE_L_addr>=EXE_L_CUR_addr,EXE_L_CUR_K,EXE_L_CUR_K+1.U)   // 相等情况即为同一mat的不同slice

      send_exe_req(setupIO=io.set_up_io,setupAddr=io.set_up_addr,rs2=EXE_deq_reg.rs2,rs1=EXE_deq_reg.rs1)
      io.set_up_io.exec:=(EXE_STATE===fire_EXE)

      val EXE_BEF_qry_port=Mat_State_Table.io.query_req(QryTabClient.EXE_BEFORE)
      EXE_BEF_qry_port.valid:=false.B
      EXE_BEF_qry_port.bits.begin_matID:=EXE_mat_addr
      EXE_BEF_qry_port.bits.end_matID:=DontCare
      EXE_BEF_qry_port.bits.extra_bufID:=DontCare
      EXE_BEF_qry_port.bits.state:=0.U

      queue_EXE.io.deq.ready:=(EXE_STATE===idle_EXE)
      
      switch(EXE_STATE)
      {
        is(idle_EXE)
        {
          when(queue_EXE.io.deq.fire)
          {
            EXE_deq_reg:=queue_EXE.io.deq.bits
            EXE_STATE:=check_LD_L_by_EXE
          }
        }
        is(check_LD_L_by_EXE)
        {
          // // Constrain
          // // 查看所需的L是否已经被加载进来了
          // EXE_STATE:=MuxCase(check_LD_L_by_EXE, Seq(
          //   (EXE_updated_K < LOAD_L_DONE_K) -> query_tab_EXE ,
          //   (EXE_updated_K===LOAD_L_DONE_K) -> Mux(EXE_L_addr<=LOAD_L_DONE_addr,query_tab_EXE,check_LD_L_by_EXE) ,
          //   (EXE_updated_K>LOAD_L_DONE_K)  -> check_LD_L_by_EXE)
          // )
          EXE_STATE:=query_tab_EXE
        }
        is(query_tab_EXE)
        {
          // 检查LoadR是否完成,并且还要标注是否需要LOAD_P
          EXE_BEF_qry_port.valid:=true.B
          when(EXE_BEF_qry_port.fire)
          {
            EXE_BEF_qry_port.bits.state := OpStates.BEFORE_RUN
            EXE_STATE := wait_query_EXE
          }
        }
        is(wait_query_EXE)
        {
          // 如果可以运行，更新exe的L指针
          when(Mat_State_Table.io.resultEn)
          {
            when(Mat_State_Table.io.result)
            {
              val same_cluster= (EXE_L_addr===EXE_L_CUR_addr)
              EXE_L_CUR_K:=Mux(EXE_L_addr>=EXE_L_CUR_addr,EXE_L_CUR_K,EXE_L_CUR_K+1.U)
              EXE_L_CUR_addr:=EXE_L_addr
            }
            EXE_STATE:=Mux(Mat_State_Table.io.result,fire_EXE,stall_EXE)
          }
        }
        is(fire_EXE)
        {
          EXE_STATE:=idle_EXE
        }
        is(stall_EXE)
        {
          EXE_STATE:=Mux((stall_counter&sysCfg.EXE_stall_tick_mask)===0.U,
                            query_tab_EXE,EXE_STATE)
        }
      }
      // *********************************************************
    
      // ******************** LOAD_P *********************
      val idle_LD_P :: query_tab_LD_P :: wait_query_LD_P :: fire_LD_P :: stall_LD_P :: update_tab_LD_P :: Nil = Enum(6)
      val LOAD_P_STATE = RegInit(idle_LD_R)
      val LOAD_P_deq_reg = RegInit(0.U.asTypeOf(new SubCmdQueue))
      val LOAD_P_Paddr_arrayAddr=sysCfg.get_array_addr_from_fullAddr(
                              LOAD_INFO.RS1.getField("basePIC_Addr")
                              .extractVal(rs1=LOAD_P_deq_reg.rs1,rs2=LOAD_P_deq_reg.rs2)(sysCfg.accessBankFullAddr_sigLen-1,0)
                        )
      val LOAD_P_DRAM_addr=LOAD_INFO.RS1.getField("baseDRAM_Addr")
                              .extractVal(rs1=LOAD_P_deq_reg.rs1,rs2=LOAD_P_deq_reg.rs2)
      send_load_P_req(load_P_req=io.load_P_req,rs2=LOAD_P_deq_reg.rs2,rs1=LOAD_P_deq_reg.rs1)
      io.load_P_req.valid:=(LOAD_P_STATE===fire_LD_P)&(!io.busy_load_P)
      
      val LD_P_qry_port=Mat_State_Table.io.query_req(QryTabClient.LD_P)
      LD_P_qry_port.valid:=false.B
      LD_P_qry_port.bits.begin_matID:=DontCare
      LD_P_qry_port.bits.end_matID:=DontCare
      LD_P_qry_port.bits.extra_bufID:=LOAD_P_Paddr_arrayAddr
      LD_P_qry_port.bits.state:=0.U

      queue_Load_P.io.deq.ready:=(LOAD_P_STATE===idle_LD_P)

      switch(LOAD_P_STATE)
      {
        is(idle_LD_P)
        {
          when(queue_Load_P.io.deq.fire)
          {
            LOAD_P_deq_reg:=queue_Load_P.io.deq.bits
            LOAD_P_STATE:=query_tab_LD_P
          }
        }
        is(query_tab_LD_P)
        {
          // Constrain
          // P buffer中的值是否已经被store，即查看P buffer是否为空 空了才能laodP（store完后由storeP设为空）
          LD_P_qry_port.valid:=true.B
          when(LD_P_qry_port.fire)
          {
            LD_P_qry_port.bits.state := OpStates.BEFORE_RUN
            LOAD_P_STATE :=wait_query_LD_P
          }
        }
        is(wait_query_LD_P)
        {
          when(Mat_State_Table.io.resultEn)
          {
            LOAD_P_STATE:=Mux(Mat_State_Table.io.result,fire_LD_P,stall_LD_P)
          }
        }
        is(fire_LD_P)
        {
            io.load_P_req.valid:=true.B
            when(io.load_P_req.fire)
            {  LOAD_P_STATE:=update_tab_LD_P}
        }
        is(stall_LD_P)
        {
          LOAD_P_STATE:=Mux((stall_counter&sysCfg.LD_P_stall_tick_mask)===0.U,
                            query_tab_LD_P,LOAD_P_STATE)
        }
        is(update_tab_LD_P)
        {
          // Constrain
          // 将LdP的flag设置为has P，这样ACC就能执行
          LD_P_qry_port.valid := (!io.busy_load_P)
          when(LD_P_qry_port.fire)
          {
            LD_P_qry_port.bits.state := OpStates.AFTER_RUN
            LOAD_P_STATE :=idle_LD_P
          }
        }
      }
      // *****************************************

      // ******************** ACC *********************
      val idle_ACC :: query_tab_check_LD_P_OR_LAST_RUN_by_ACC  :: wait_query_check_LD_P_OR_LAST_RUN_by_ACC :: query_tab_check_EXE_by_ACC :: wait_query_check_EXE_by_ACC :: fire_ACC::stall_ACC::update_tab_ACC::Nil = Enum(8)
      val ACC_STATE = RegInit(idle_ACC)
      val ACC_deq_reg = RegInit(0.U.asTypeOf(new SubCmdQueue))
      val ACC_src_mat_addr=sysCfg.get_full_matID(
                          ACC_INFO.RS1.getField("PIC_Addr_src")
                          .extractVal(rs1=ACC_deq_reg.rs1,rs2=ACC_deq_reg.rs2)(sysCfg.accessBankFullAddr_sigLen-1,0)
                      )
      val ACC_src_mat_num=ACC_INFO.RS2.getField("Parameters").getField("SrcNum")
                      .extractVal(rs1=ACC_deq_reg.rs1,rs2=ACC_deq_reg.rs2)  
      val ACC_dest_array_addr=sysCfg.get_array_addr_from_fullAddr(ACC_INFO.RS1.getField("PIC_Addr_dest")
                              .extractVal(rs1=ACC_deq_reg.rs1,rs2=ACC_deq_reg.rs2)(sysCfg.accessBankFullAddr_sigLen-1,0)
                            )  // P buffer addr
      val ACC_is_last=ACC_INFO.RS2.getField("Parameters").getField("If_last")
                      .extractVal(rs1=ACC_deq_reg.rs1,rs2=ACC_deq_reg.rs2)
      val ACC_need_P=ACC_INFO.RS2.getField("Parameters").getField("need_P")
                      .extractVal(rs1=ACC_deq_reg.rs1,rs2=ACC_deq_reg.rs2)

      send_accumulate_req(accReq=io.accumulate_req,rs2=ACC_deq_reg.rs2,rs1=ACC_deq_reg.rs1)
      io.accumulate_req.valid := (ACC_STATE===fire_ACC)&(!io.busy_acc)
      
      val ACC_qry_port=Mat_State_Table.io.query_req(QryTabClient.ACC)
      ACC_qry_port.valid:=false.B
      ACC_qry_port.bits.begin_matID:=ACC_src_mat_addr
      ACC_qry_port.bits.end_matID:=ACC_src_mat_addr+ACC_src_mat_num-1.U
      ACC_qry_port.bits.extra_bufID:=ACC_dest_array_addr
      ACC_qry_port.bits.state:=0.U

      queue_ACC.io.deq.ready:=(ACC_STATE===idle_ACC)
      
      switch(ACC_STATE)
      {
        is(idle_ACC)
        {
            when(queue_ACC.io.deq.fire)
            {
              ACC_deq_reg:=queue_ACC.io.deq.bits
              ACC_STATE:=query_tab_check_LD_P_OR_LAST_RUN_by_ACC
            }
        }
        is(query_tab_check_LD_P_OR_LAST_RUN_by_ACC)
        {
          // Constrain
          // 1.检查P有没有加载到buffer里头了
          // 2.检查上一轮计算是否结束，因为即使未store，ACC的运行也会满足目前条件
            ACC_qry_port.valid:=true.B
            when(ACC_qry_port.fire)
            {
              ACC_qry_port.bits.state := Mux(ACC_need_P.asBool,OpStates.ACC_BEFORE_RUN_QUERY_LD_P,OpStates.BEFORE_RUN)
              ACC_STATE := wait_query_check_LD_P_OR_LAST_RUN_by_ACC
            }
        }
        is(wait_query_check_LD_P_OR_LAST_RUN_by_ACC)
        {
           when(Mat_State_Table.io.resultEn)
          {
            ACC_STATE:=Mux(Mat_State_Table.io.result===QueryResType.CANNT_RUN,stall_ACC,query_tab_check_EXE_by_ACC)
          }
        }
        is(query_tab_check_EXE_by_ACC) // constrain 
        {
          // Constrain
          // 查看exe是不是跑完了,如果此步通过，修改acc flag为fired
          ACC_qry_port.valid:=true.B
          when(ACC_qry_port.fire)
          {
            ACC_qry_port.bits.state := OpStates.ACC_BEFORE_RUN_EXE
            ACC_STATE :=wait_query_check_EXE_by_ACC
          }
        }
        is(wait_query_check_EXE_by_ACC)
        {
          when(Mat_State_Table.io.resultEn)
          {
            ACC_STATE:=Mux(Mat_State_Table.io.result===QueryResType.CANNT_RUN,stall_ACC,fire_ACC)
          }
        }
        is(fire_ACC)
        {
          when(io.accumulate_req.fire)
          { ACC_STATE:=update_tab_ACC }
        }
        is(stall_ACC)
        {
          ACC_STATE:=Mux((stall_counter&sysCfg.ACC_stall_tick_mask)===0.U,
                            query_tab_check_LD_P_OR_LAST_RUN_by_ACC,ACC_STATE)
        }
        is(update_tab_ACC)
        {
          // Constrain
          // ACC更新状态，给storeP标记可以store，这样store才能执行
          // 同时要将计算的mat的exe标记为可以进行新的EXE（清空EXE flag即可）
          ACC_qry_port.valid := (!io.busy_acc)
          when(ACC_qry_port.fire)
          {
            ACC_qry_port.bits.state := Mux(ACC_is_last.asBool,OpStates.ACC_AFTER_RUN_IS_LAST,OpStates.AFTER_RUN)
            ACC_STATE := idle_ACC
          }
        }
      }

      // ******************* Store P **********************
      val idle_ST_P :: query_tab_LD_P_by_ST_P :: wait_query_ST_P  :: stall_ST_P :: fire_ST_P :: update_tab_ST_P :: Nil = Enum(6)
      val ST_P_STATE = RegInit(idle_ST_P)
      val ST_P_deq_reg = RegInit(0.U.asTypeOf(new SubCmdQueue))
      val ST_P_buf_addr = sysCfg.get_array_addr_from_fullAddr(ST_P_INFO.RS1.getField("basePIC_Addr")
                      .extractVal(rs1=ST_P_deq_reg.rs1,rs2=ST_P_deq_reg.rs2)(sysCfg.accessBankFullAddr_sigLen-1,0))  // P buffer addr
      val ST_P_dram_addr = ST_P_INFO.RS1.getField("baseDRAM_Addr")
                      .extractVal(rs1=ST_P_deq_reg.rs1,rs2=ST_P_deq_reg.rs2)
      send_store_P_req(store_P_req=io.store_P_req,rs2=ST_P_deq_reg.rs2,rs1=ST_P_deq_reg.rs1)
      io.store_P_req.valid:=(ST_P_STATE===fire_ST_P)&(!io.busy_store_P)
      
      val ST_P_qry_port=Mat_State_Table.io.query_req(QryTabClient.ST_P)
      ST_P_qry_port.valid:=false.B
      ST_P_qry_port.bits.begin_matID:=DontCare
      ST_P_qry_port.bits.end_matID:=DontCare
      ST_P_qry_port.bits.extra_bufID:=ST_P_buf_addr
      ST_P_qry_port.bits.state:=0.U

      queue_STORE_P.io.deq.ready:=(ST_P_STATE===idle_ST_P)
      
      switch(ST_P_STATE)
      {
        is(idle_ST_P)
        {
          queue_STORE_P.io.deq.ready:=true.B
          when(queue_STORE_P.io.deq.fire)
          {
            ST_P_deq_reg:=queue_STORE_P.io.deq.bits
            ST_P_STATE:=Mux(trace_assert_st_P,fire_ST_P,query_tab_LD_P_by_ST_P)
          }
        }
        is(query_tab_LD_P_by_ST_P)
        {
          ST_P_qry_port.valid:=true.B
          when(ST_P_qry_port.fire)
          {
            // Constrain
            // 检查storeP是否可以运行，由ACC修改的标志位，即ACC完成没
            ST_P_qry_port.bits.state := OpStates.BEFORE_RUN
            ST_P_STATE := wait_query_ST_P
          }
        }
        is(wait_query_ST_P)
        {
            when(Mat_State_Table.io.resultEn)
            {
              val query_res=Mat_State_Table.io.result
              ST_P_STATE:=Mux(query_res===QueryResType.CANNT_RUN,stall_ST_P,fire_ST_P)
            }
        }
        is(stall_ST_P)
        {
          ST_P_STATE:=Mux((stall_counter&sysCfg.STORE_P_stall_tick_mask)===0.U,
                        query_tab_LD_P_by_ST_P,ST_P_STATE)
        }
        is(fire_ST_P)
        {
          when(io.store_P_req.fire)
          { 
            ST_P_STATE:=update_tab_ST_P  
            trace_assert_st_P:=false.B
          }
        }
        is(update_tab_ST_P)
        {
          // Constrain两个
          // 清空标志位LOADP，ACC，表示本轮计算结束，以及是否清空LoadR标志位 line319
          // 更新已经完成计算的P指针
          ST_P_qry_port.valid:= (!io.busy_store_P)
          when(ST_P_qry_port.fire)
          {
            ST_P_qry_port.bits.state := OpStates.AFTER_RUN
            STORE_P_DONE_addr:=ST_P_dram_addr
            STORE_P_DONE_K:=Mux(ST_P_dram_addr<STORE_P_DONE_addr,STORE_P_DONE_K+1.U,STORE_P_DONE_K)
            ST_P_STATE:=idle_ST_P
          }
        }
      }

      // busy signal
      normal_cmd_sche_free:= (LOAD_L_STATE===idle_LD_L)&(LOAD_R_STATE===idle_LD_R)&(LOAD_P_STATE===idle_LD_P)&
                              (ACC_STATE===idle_ACC)&(ST_P_STATE===idle_ST_P)
    

      // ******************** EXE Finish tracker *********************
      // EXE完成之后，修改对应的table中的flag
      val begin_matID=sysCfg.first_matID
      val end_matID=sysCfg.last_matID

      val query_mat_ptr=RegInit(begin_matID.U(log2Ceil(sysCfg.total_valid_mats).W))
      // val mat_busy_rec_vec=RegInit(VecInit(Seq.fill(sysCfg.total_valid_mats){false.B}))
      // val all_mat_free = mat_busy_rec_vec.forall(_ === false.B)

      val mat_busy_rec_choose=WireInit(false.B)

      val query :: query_mat_table :: Nil = Enum(2)
      val exe_finish_trace_state = RegInit(query)

      io.query_mat_req.valid:= (exe_finish_trace_state===query)
      
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

    io.query_mat_req.bits.matID:=query_mat_ptr

    val EXE_AFT_qry_port=Mat_State_Table.io.query_req(QryTabClient.EXE_AFTER)
    EXE_AFT_qry_port.valid:= (exe_finish_trace_state===query_mat_table)
    EXE_AFT_qry_port.bits.begin_matID:=query_mat_ptr
    EXE_AFT_qry_port.bits.end_matID:=DontCare
    EXE_AFT_qry_port.bits.extra_bufID:=DontCare
    EXE_AFT_qry_port.bits.state := OpStates.AFTER_RUN

    switch(exe_finish_trace_state)
    {
        is(query)
        {
            when(io.query_mat_req.fire)
            {
                query_mat_ptr:=Mux(no_change||start,Mux(query_mat_ptr===end_matID.U,begin_matID.U,query_mat_ptr+1.U),query_mat_ptr)
                exe_finish_trace_state:=Mux(no_change||start,exe_finish_trace_state,query_mat_table)
                for(i<-0 until sysCfg.total_valid_mats)
                {
                    when(query_mat_ptr===i.U & !no_change)
                    {
                        mat_busy_rec_vec(i):= !mat_busy_rec_vec(i)
                    }
                }
            }
        }
        is(query_mat_table) // detect finfish
        {
          when(EXE_AFT_qry_port.fire)
          {
            query_mat_ptr:=Mux(query_mat_ptr===end_matID.U,begin_matID.U,query_mat_ptr+1.U)
            exe_finish_trace_state:=query
          }
        }
    }
 
 
    def enq_sub_queue(sub_queue: Queue[SubCmdQueue],rs1:UInt,rs2:UInt) : Unit={
        sub_queue.io.enq.bits.rs1:=rs1
        sub_queue.io.enq.bits.rs2:=rs2
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


