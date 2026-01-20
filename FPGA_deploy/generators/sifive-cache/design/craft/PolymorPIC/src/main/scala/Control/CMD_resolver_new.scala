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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.Buffer

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.regmapper._


// Warp it again and add cmdID
class SetUpIOFull(sysCfg:Sys_Config) extends Bundle
{
  val setUpIO=new SetUpIO(sysCfg.core_config)
  val setUpAddr=UInt(log2Ceil(sysCfg.totalMatNum).W)
  val cmdID=UInt(sysCfg.cmdID_sigLen.W)
}

class P2SL_Req_Full(sysCfg:Sys_Config)  extends Bundle
{
  val p2sL_req=new P2S_L_req(sysCfg)
  val cmdID=UInt(sysCfg.cmdID_sigLen.W)
}

class P2SR_Req_Full(sysCfg:Sys_Config)  extends Bundle
{
  val p2sR_req=new P2S_R_req(sysCfg)
  val if_T=Bool()
  val cmdID=UInt(sysCfg.cmdID_sigLen.W)
}

class LD_P_Req_Full(sysCfg:Sys_Config)  extends Bundle
{
  val ld_P_req=new Load_req(sysCfg)
  val cmdID=UInt(sysCfg.cmdID_sigLen.W)
}

class ST_P_Req_Full(sysCfg:Sys_Config)  extends Bundle
{
  val st_P_req=new Store_req(sysCfg)
  val cmdID=UInt(sysCfg.cmdID_sigLen.W)
}

class Accumu_Req_Full(sysCfg:Sys_Config) extends Bundle
{
  val accReq=new Accumu_req(sysCfg)
  val cmdID=UInt(sysCfg.cmdID_sigLen.W)
}

class CMD_resolver(sysCfg:Sys_Config)(implicit p: Parameters) extends LazyModule 
{
  val addr_MMIO=MMIO.cmd_enq_MMIO_bigInt
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

      // Send req to p2s_R_T
      val p2s_R_T_req=Decoupled(new P2S_R_req(sysCfg))
      val busy_p2s_R_T=Input(Bool())

      // Send req to p2s_L
      val p2s_L_req=Decoupled(new P2S_L_req(sysCfg))
      val busy_p2s_L=Input(Bool())

      // Send req to im2col
      val im2col_req=Decoupled(new IN2COL_req(sysCfg))
      val im2col_busy=Input(Bool())

      // Send req to General Load (In scheduling, directly requesting load is for P's load)
      val load_P_req=Decoupled(new Load_req(sysCfg))
      val busy_load=Input(Bool())

      // Send req to General Store (In scheduling, directly requesting store is for P's store)
      val store_P_req=Decoupled(new Store_req(sysCfg))
      val busy_store=Input(Bool())

      // Send req to accumulator
      val accumulate_req= Decoupled(new Accumu_req(sysCfg))
      val busy_acc= Input(Bool())

      // Send req to set_up
      val set_up_addr=Output(UInt(log2Ceil(sysCfg.totalMatNum).W))
      val set_up_io=Output(new SetUpIO(sysCfg.core_config))

      // Send and receive switch req and resp
      val switch_req=Decoupled(new SwitchInfo(sysCfg))
      val switch_resp=Flipped(Decoupled(new SwitchResult(sysCfg)))

      // state query
      val query_mat_req=Decoupled(new QueryMatReq(sysCfg))
      val mat_busy=Input(Bool())

    })

    // ISA info
    val isaInfo=NewISA(sysCfg)
    // Set reg info
    val setRegInfo=new SetRegsInfo(sysCfg)
    // Register files
    val regFiles=Module(new RegFile(sysCfg))
    val regsOut=regFiles.io.regOut
    val moduleID=regsOut.moduleID
    val cmdID = regsOut.cmdID
    // Execution type
    val isQueryCmd= (moduleID===FuncModule.QUERY_ID.U)
    val isImmedCmd= (moduleID===FuncModule.IM2COL_ID.U)|(moduleID===FuncModule.SWITCH_ID.U)
    val isEnqCmd= (!isQueryCmd)&(!isImmedCmd)

    // TL
    val (rocc_tl, rocc_edge) = reveice_cmd_node.in(0)
    val regMM0 = RegInit(0.U(64.W))
    val regMM1 = RegInit(0.U(64.W))
    val regMM2 = RegInit(0.U(64.W))
    val regMM3 = RegInit(0.U(64.W))
    reveice_cmd_node.regmap(
        0x00 -> Seq(RegField(64,regMM0)),
        0x08 -> Seq(RegField(64,regMM1)),
        0x10 -> Seq(RegField(64,regMM2)),
        0x18 -> Seq(RegField(64,regMM3)),
    )

    val rev_idle  :: set_d_resp::enq_state :: exe_immed :: set_cmd_state :: Nil = Enum(5)
    val cmd_rev_state = RegInit(rev_idle)
    val isRegEnqStage=(cmd_rev_state===enq_state)
    rocc_tl.a.ready:= (cmd_rev_state===rev_idle)

    // Set register file
    val setRegType=(rocc_tl.a.bits.address-addr_MMIO.U)>>3
    val setRegTypeReg=RegInit(0.U(2.W))
    val receiveRoccSig=(cmd_rev_state===rev_idle)&(rocc_tl.a.fire)
    regFiles.io.setSrc      :=  receiveRoccSig&(setRegType===setRegInfo.setSrcInfo.roccOpCode.U)
    regFiles.io.setDst      :=  receiveRoccSig&(setRegType===setRegInfo.setDstInfo.roccOpCode.U)
    regFiles.io.setSize     :=  receiveRoccSig&(setRegType===setRegInfo.setSizeInfo.roccOpCode.U)
    regFiles.io.setParam    :=  receiveRoccSig&(setRegType===setRegInfo.setParamInfo.roccOpCode.U)
    regFiles.io.setRegInput :=  rocc_tl.a.bits.data

    // For execute switch and im2col
    val switch_idle :: switch_req :: switch_resp :: Nil = Enum(3)
    val switch_state = RegInit(switch_idle)
    val switch_free = (switch_state===switch_idle)

    val im2col_idle :: im2col_req :: wait_im2col_finish :: Nil = Enum(3)
    val im2col_state = RegInit(im2col_idle)
    val im2col_free=(im2col_state===im2col_idle)

    // Command State Table
    val cmd_state_helper=Module(new CmdStateHelper(sysCfg,sysCfg.core_config))

    val setterIO=cmd_state_helper.io.query_req(QryTabClient.MAIN_SETTER)
    setterIO.valid:=false.B
    setterIO.bits.cmdID:=DontCare
    setterIO.bits.is_finish:=DontCare

    val readerIO=cmd_state_helper.io.query_req(QryTabClient.READER)
    readerIO.valid:=false.B
    readerIO.bits.cmdID:=DontCare
    readerIO.bits.is_finish:=DontCare

    // Each mat's state
    val mat_busy_rec_vec=RegInit(VecInit(Seq.fill(sysCfg.totalMatNum){false.B}))
    val mat_cmdID_table = SyncReadMem(sysCfg.totalMatNum,UInt(sysCfg.cmdID_sigLen.W))
    val mat_cmdID_writeEn_wire = WireInit(false.B)
    val mat_cmdID_en_wire = WireInit(false.B)
    val mat_cmdID_addr_wire=WireInit(0.U(log2Ceil(sysCfg.totalMatNum).W))
    val mat_cmdID_data_wire=WireInit(0.U(sysCfg.cmdID_sigLen.W))
    when(mat_cmdID_writeEn_wire & mat_cmdID_en_wire){
        mat_cmdID_table.write(mat_cmdID_addr_wire,mat_cmdID_data_wire)
    }
    val mat_cmdID_out_wire=mat_cmdID_table.read(mat_cmdID_addr_wire, mat_cmdID_en_wire&&(!mat_cmdID_writeEn_wire))

    val all_mat_free = mat_busy_rec_vec.forall(_ === false.B)

    // SubQueue Part >>>>>>>>>>>>>>>>>>>>>>>>>>>>>
    val queue_Load_L = Module(new Queue(new P2SL_Req_Full(sysCfg), 6))
    val queue_Load_L_has_val=queue_Load_L.io.deq.valid
    val queue_Load_L_full = !queue_Load_L.io.enq.ready
    val queue_Load_L_reject= queue_Load_L_full&(moduleID===FuncModule.P2SL_ID.U)
    queue_Load_L.io.enq.valid := isRegEnqStage&(moduleID===FuncModule.P2SL_ID.U)

    val queue_Load_R = Module(new Queue(new P2SR_Req_Full(sysCfg), 8))
    val queue_Load_R_has_val = queue_Load_R.io.deq.valid
    val queue_Load_R_full = !queue_Load_R.io.enq.ready
    val queue_Load_R_reject=queue_Load_R_full&(moduleID===FuncModule.P2SR_ID.U)
    queue_Load_R.io.enq.valid := isRegEnqStage&(moduleID===FuncModule.P2SR_ID.U)

    val queue_Load_P = Module(new Queue(new LD_P_Req_Full(sysCfg), 4))
    val queue_Load_P_has_val = queue_Load_P.io.deq.valid
    val queue_Load_P_full= !queue_Load_P.io.enq.ready
    val queue_Load_P_reject=queue_Load_P_full&(moduleID===FuncModule.LOAD_ID.U)
    queue_Load_P.io.enq.valid := isRegEnqStage&(moduleID===FuncModule.LOAD_ID.U)

    val queue_EXE = Module(new Queue(new SetUpIOFull(sysCfg), 12))
    val queue_EXE_has_val=queue_EXE.io.deq.valid
    val queue_EXE_full=(!queue_EXE.io.enq.ready)
    val queue_EXE_reject=queue_EXE_full&(moduleID===FuncModule.EXE_ID.U)
    queue_EXE.io.enq.valid := isRegEnqStage&(moduleID===FuncModule.EXE_ID.U)

    val queue_STORE_P = Module(new Queue(new ST_P_Req_Full(sysCfg), 4))
    val queue_STORE_P_has_val=queue_STORE_P.io.deq.valid
    val queue_STORE_P_full=(!queue_STORE_P.io.enq.ready)
    val queue_STORE_P_reject=queue_STORE_P_full&(moduleID===FuncModule.STORE_ID.U)
    queue_STORE_P.io.enq.valid:= isRegEnqStage&(moduleID===FuncModule.STORE_ID.U)

    val queue_ACC = Module(new Queue(new Accumu_Req_Full(sysCfg), 16))
    val queue_ACC_has_val=queue_ACC.io.deq.valid
    val queue_ACC_full=(!queue_ACC.io.enq.ready)
    val queue_ACC_reject=queue_ACC_full&(moduleID===FuncModule.ACC_ID.U)
    queue_ACC.io.enq.valid:= isRegEnqStage&(moduleID===FuncModule.ACC_ID.U)

    val rejectEnq=queue_Load_L_reject|queue_Load_R_reject|queue_Load_P_reject|queue_EXE_reject|queue_STORE_P_reject|queue_ACC_reject
    // <<<<<<<<<<<<<<<<<<<<< SubQueue Part 

    // Connection queue<-> regFile
    enq_LOAD(regsOut,queue_Load_P)
    enq_P2SL(regsOut,queue_Load_L)
    enq_P2SR(regsOut,queue_Load_R)
    enq_ACC(regsOut,queue_ACC)
    enq_EXE(regsOut,queue_EXE)
    enq_STORE(regsOut,queue_STORE_P)
    // What the type of query?
    val is_quey_immed_cmd=is_quey_immed_cmd_func(regsOut).asBool
    // <<<<<<<<<<<<<<<<<<<< queue<-> regFile

    // <<<<<<<<<<<<<<<<<<<< reqPort<->queue
    io.p2s_R_req.bits:=queue_Load_R.io.deq.bits.p2sR_req
    io.p2s_R_T_req.bits:=queue_Load_R.io.deq.bits.p2sR_req
    io.p2s_L_req.bits:=queue_Load_L.io.deq.bits.p2sL_req
    io.load_P_req.bits:=queue_Load_P.io.deq.bits.ld_P_req
    io.store_P_req.bits:=queue_STORE_P.io.deq.bits.st_P_req
    io.accumulate_req.bits:=queue_ACC.io.deq.bits.accReq
    io.set_up_io:=queue_EXE.io.deq.bits.setUpIO
    io.set_up_addr:=queue_EXE.io.deq.bits.setUpAddr
    // reqPort<->queue

    val all_queue_free =  (!queue_Load_L_has_val)&
                          (!queue_Load_R_has_val)&
                          (!queue_Load_P_has_val)&
                          (!queue_EXE_has_val)&
                          (!queue_ACC_has_val)&
                          (!queue_STORE_P_has_val)

    val no_exe_executing= all_mat_free

    val no_normal_cmd_executing= (!io.busy_p2s_R_T)&(!io.busy_p2s_R)&(!io.busy_p2s_L)&(!io.busy_acc)&(!io.busy_store)
    val normal_cmd_sche_free=Wire(Bool())
    val all_sche_free= switch_free&normal_cmd_sche_free&im2col_free
    val no_immed_running=switch_free&im2col_free

    // All queues are empty, no modules are executing, and the scheduler has no immed instructions (init, switch, query) running.
    val total_free= all_queue_free&no_exe_executing&no_normal_cmd_executing&all_sche_free
    
    val enq_happen =  (queue_Load_L.io.enq.fire)||
                      (queue_Load_R.io.enq.fire)||
                      (queue_Load_P.io.enq.fire)||
                      (queue_EXE.io.enq.fire)||
                      (queue_STORE_P.io.enq.fire)||
                      (queue_ACC.io.enq.fire)

    // switch
    val switch_op_success=RegInit(false.B)
    val switch_op_matID_begin=RegInit(0.U(log2Ceil(sysCfg.totalMatNum).W))
    val switch_op_matID_end=RegInit(0.U(log2Ceil(sysCfg.totalMatNum).W))
    send_SWITCH_req(pic_switch_req=io.switch_req,regOut=regsOut) // rs2 is unused, so it doesn't matter.
    io.switch_req.valid:=(switch_state===switch_req)&all_queue_free&no_exe_executing
    io.switch_resp.ready:=(switch_state===switch_resp)

    // IM2COL
    send_IM2COL_req(im2col_req=io.im2col_req,regOut=regsOut)
    io.im2col_req.valid:=(im2col_state===im2col_req)

    // immediately return command : like query
    val initISA=isaInfo.QUERY_info
    val returnField=initISA.returnRdInfo
    val is_cmd_finish=RegInit(false.B)
    is_cmd_finish:=RegEnable(cmd_state_helper.io.is_finish,RegNext(readerIO.fire))

    // The order must match the corresponding fields in case class ISA_QUERY.
    val d_channel_return_val=Cat(0.U((64-switch_op_matID_end.getWidth*2-3).W),
                                switch_op_matID_end,
                                switch_op_matID_begin,
                                switch_op_success,
                                (!total_free),
                                is_cmd_finish)
    rocc_tl.d.bits.data := d_channel_return_val

    // Main deal logic
    val query_valid=RegInit(false.B) // The result is returned only on the second query; the query must be issued twice.
    switch(cmd_rev_state)
    {
        is(rev_idle)
        {
            when(rocc_tl.a.fire)
            {
              // At this stage, write the received data into the regFile
              // If it is a setReg type execution, set the d channel information
              cmd_rev_state:=Mux(setRegType===setRegInfo.setParamInfo.roccOpCode.U,
                                set_d_resp,
                                rev_idle)
            }
        }
        is(set_d_resp) // is execution type
        {
          // Execute immed instructions: check if any queue instructions are running, if so reject.
          // For enq instructions: check if any immed instructions are running, check if the queue has space.
          val targetQueueFull = rejectEnq       // Whether the target queue is full.

          // ---------reply to rejection case-------------
          when(isImmedCmd)
          {
            // Is an immed instruction: 
            // check if any queue instructions are running, check if any immed instructions are running, if so reject.
            when(!total_free)
            {
              rocc_tl.d.bits.data:="hFFFFFFFFFFFFFFFF".U(64.W)
              cmd_rev_state:=Mux(rocc_tl.d.fire,rev_idle,cmd_rev_state)
            }
            .otherwise
            {
              cmd_rev_state:=exe_immed
            }
          }
          .elsewhen(isEnqCmd)
          {
            // The enq instruction checks :
            // whether an immed instruction is currently executing and whether the target queue is empty.
            when(targetQueueFull|(!no_immed_running))
            {
              rocc_tl.d.bits.data:="hFFFFFFFFFFFFFFFF".U(64.W)
              cmd_rev_state:=Mux(rocc_tl.d.fire,rev_idle,cmd_rev_state)
            }
            .otherwise
            {
              cmd_rev_state:=enq_state
            }
          }
          .otherwise
          {
            // Query instruction that returns status.
            // Immediate-running instructions bypass the CMD state table.
            // Each query on the C side must be continuous and non-interruptible.
            when(is_quey_immed_cmd)
            {
              cmd_rev_state:=rev_idle
            }
            .elsewhen(query_valid)
            {
              query_valid:=false.B
              cmd_rev_state:=rev_idle
            }
            // Do query
            .otherwise
            {
              readerIO.valid:=true.B
              when(readerIO.fire)
              {
                readerIO.bits.cmdID:=cmdID
                query_valid:=true.B
                cmd_rev_state:=rev_idle
              }
            }
          }
        }
        is(exe_immed)
        {
            switch_state:=Mux((moduleID===FuncModule.SWITCH_ID.U),switch_req,switch_state)
            im2col_state:=Mux((moduleID===FuncModule.IM2COL_ID.U),im2col_req,im2col_state)
            cmd_rev_state:=rev_idle
        }
        is(enq_state)
        {
          cmd_rev_state:=Mux(enq_happen,set_cmd_state,cmd_rev_state)
        }
        is(set_cmd_state)
        {
           setterIO.valid:=true.B
           when(setterIO.fire)
           {
              setterIO.bits.cmdID:=cmdID
              setterIO.bits.is_finish:=false.B
              cmd_rev_state:=rev_idle
           }
        }
    }
 
    // Global cycle timestamp
    val timeStamp=RegInit(0.U(64.W))
    timeStamp:=timeStamp+1.U

    // ********************** Execute mode Switch ************************
    // switch
    switch(switch_state)
    {
      is(switch_req)
      {
        when(io.switch_req.fire)
        { switch_state:=switch_resp}
      }
      is(switch_resp)
      {
        when(io.switch_resp.fire)
        {
          switch_op_success:=io.switch_resp.bits.op_success
          switch_op_matID_end:=(sysCfg.totalMatNum-1).U
          switch_op_matID_begin:=io.switch_resp.bits.avail_MatID_begin
          switch_state:=switch_idle
        }
      }
    }

    // ********************** Execute IM2COL ************************
    switch(im2col_state)
    {
      is(im2col_req)
      {
        when(io.im2col_req.fire)
        { 
          im2col_state:=wait_im2col_finish  
        }
      }
      is(wait_im2col_finish)
      {
        when(!io.im2col_busy)
        {printf("IM2COL End 0x%x\n",timeStamp)}
        im2col_state:=Mux(io.im2col_busy,im2col_state,im2col_idle)
      }
    }

    // ##########################################################################################
    // The following commands are scheduled in queue
    // ##########################################################################################
    // ********************** Execute LOAD_L ************************
    val idle_LD_L :: fire_LD_L :: wait_LD_L_FINISH :: set_finish :: Nil = Enum(4)
    val LOAD_L_STATE = RegInit(idle_LD_L)
    val LD_L_cmdID=RegInit(0.U(sysCfg.cmdID_sigLen.W))

    io.p2s_L_req.valid := (!io.busy_p2s_L)&(LOAD_L_STATE===fire_LD_L)

    queue_Load_L.io.deq.ready:=(LOAD_L_STATE===fire_LD_L)&io.p2s_L_req.fire

    val LD_L_qry_port=cmd_state_helper.io.query_req(QryTabClient.LD_L)
    LD_L_qry_port.valid:=false.B
    LD_L_qry_port.bits.cmdID:=LD_L_cmdID
    LD_L_qry_port.bits.is_finish:=false.B


    switch(LOAD_L_STATE)
    {
      is(idle_LD_L)
      {
        when(queue_Load_L.io.deq.valid)
        {  LOAD_L_STATE:=fire_LD_L }
      }
      is(fire_LD_L)
      {
        when(io.p2s_L_req.fire)
        { 
          LD_L_cmdID:=queue_Load_L.io.deq.bits.cmdID
          LOAD_L_STATE:=wait_LD_L_FINISH 
        }
      }
      is(wait_LD_L_FINISH)
      {
        LOAD_L_STATE:=Mux(!io.busy_p2s_L,set_finish,LOAD_L_STATE)
      }
      is(set_finish)
      {
        LD_L_qry_port.valid:=true.B
        when(LD_L_qry_port.fire)
        {
          LD_L_qry_port.bits.is_finish:=true.B
          LOAD_L_STATE:=idle_LD_L
        }
      }
    }


    // ********************** Execute LOAD_R ************************
    val idle_LD_R :: fire_LD_R  :: wait_LD_R_FINISH :: set_finish_LD_R :: Nil = Enum(4)
    val LOAD_R_STATE = RegInit(idle_LD_R)
    val LD_R_cmdID=RegInit(0.U(sysCfg.cmdID_sigLen.W))

    // p2sR type
    val if_T=queue_Load_R.io.deq.bits.if_T
    io.p2s_R_req.valid:= (!io.busy_p2s_R)&(LOAD_R_STATE===fire_LD_R)&(!if_T)
    io.p2s_R_T_req.valid:= (!io.busy_p2s_R_T)&(LOAD_R_STATE===fire_LD_R)&(if_T)

    queue_Load_R.io.deq.ready:=(LOAD_R_STATE===fire_LD_R)&(io.p2s_R_req.fire||io.p2s_R_T_req.fire)

    val LD_R_qry_port=cmd_state_helper.io.query_req(QryTabClient.LD_R)
    LD_R_qry_port.valid:=false.B
    LD_R_qry_port.bits.cmdID:=LD_R_cmdID
    LD_R_qry_port.bits.is_finish:=false.B
    
    switch(LOAD_R_STATE)
    {
      is(idle_LD_R)
      {
        when(queue_Load_R.io.deq.valid)
        { LOAD_R_STATE:=fire_LD_R  }
      }
      is(fire_LD_R)
      {
        when(io.p2s_R_req.fire||io.p2s_R_T_req.fire)
        { 
          LD_R_cmdID:=queue_Load_R.io.deq.bits.cmdID
          LOAD_R_STATE:=wait_LD_R_FINISH 
        }
      }
      is(wait_LD_R_FINISH)
      {
        val is_finish= (!io.busy_p2s_R)&(!io.busy_p2s_R_T)
        LOAD_R_STATE := Mux(is_finish,set_finish_LD_R,LOAD_R_STATE)
      }
      is(set_finish_LD_R)
      {
        LD_R_qry_port.valid:=true.B
        when(LD_R_qry_port.fire)
        {
          LD_R_qry_port.bits.is_finish:=true.B
          LOAD_R_STATE:=idle_LD_R
        }
      }
    }
    // **********************************************************

    // ********************** Execute EXE ************************
    val idle_EXE :: fire_EXE  :: Nil = Enum(2)
    val EXE_STATE = RegInit(idle_EXE)
    val EXE_deq_data = queue_EXE.io.deq.bits
    val EXE_MATID = EXE_deq_data.setUpAddr
    val EXE_cmdID = EXE_deq_data.cmdID

    io.set_up_io.exec:=(EXE_STATE===fire_EXE)
    queue_EXE.io.deq.ready:=(EXE_STATE===fire_EXE)
    
    switch(EXE_STATE)
    {
      is(idle_EXE)
      {
        when(queue_EXE.io.deq.valid)
        { 
          EXE_STATE:=fire_EXE 
        }
      }
      is(fire_EXE)
      {
        mat_cmdID_writeEn_wire:=true.B
        mat_cmdID_en_wire:=true.B
        mat_cmdID_addr_wire:=EXE_MATID
        mat_cmdID_data_wire:=EXE_cmdID
        EXE_STATE:=idle_EXE
      }
    }
    // *********************************************************
    
    // ******************** LOAD_P *********************
    val idle_LD_P :: fire_LD_P :: wait_LD_P_FINISH :: set_finish_LD_P :: Nil = Enum(4)
    val LOAD_P_STATE = RegInit(idle_LD_R)
    val LD_P_cmdID=RegInit(0.U(sysCfg.cmdID_sigLen.W))
    
    io.load_P_req.valid:=(LOAD_P_STATE===fire_LD_P)&(!io.busy_load)
    
    queue_Load_P.io.deq.ready:=(LOAD_P_STATE===fire_LD_P)&io.load_P_req.fire

    val LD_P_qry_port=cmd_state_helper.io.query_req(QryTabClient.LD_P)
    LD_P_qry_port.valid:=false.B
    LD_P_qry_port.bits.cmdID:=LD_P_cmdID
    LD_P_qry_port.bits.is_finish:=false.B

    switch(LOAD_P_STATE)
    {
      is(idle_LD_P)
      {
        when(queue_Load_P.io.deq.valid)
        { LOAD_P_STATE:=fire_LD_P  }
      }
      is(fire_LD_P)
      {
          when(io.load_P_req.fire)
          { 
            LD_P_cmdID:=queue_Load_P.io.deq.bits.cmdID
            LOAD_P_STATE:=wait_LD_P_FINISH
          }
      }
      is(wait_LD_P_FINISH)
      {
        LOAD_P_STATE :=Mux(!io.busy_load,set_finish_LD_P,LOAD_P_STATE)
      }
      is(set_finish_LD_P)
      {
        LD_P_qry_port.valid:=true.B
        when(LD_P_qry_port.fire)
        {
          LD_P_qry_port.bits.is_finish:=true.B
          LOAD_P_STATE:=idle_LD_P
        }
      }
    }
    // *****************************************

    // ******************** ACC *********************
    val idle_ACC :: fire_ACC :: wait_ACC_FINISH :: set_finish_ACC :: Nil = Enum(4)
    val ACC_STATE = RegInit(idle_ACC)
    val ACC_cmdID=RegInit(0.U(sysCfg.cmdID_sigLen.W))

    io.accumulate_req.valid := (ACC_STATE===fire_ACC)&(!io.busy_acc)

    queue_ACC.io.deq.ready:=(ACC_STATE===fire_ACC)&io.accumulate_req.fire

    val ACC_qry_port=cmd_state_helper.io.query_req(QryTabClient.ACC)
    ACC_qry_port.valid:=false.B
    ACC_qry_port.bits.cmdID:=ACC_cmdID
    ACC_qry_port.bits.is_finish:=false.B
      
    switch(ACC_STATE)
    {
      is(idle_ACC)
      {
          when(queue_ACC.io.deq.valid)
          { ACC_STATE:=fire_ACC }
      }
      is(fire_ACC)
      {
        when(io.accumulate_req.fire)
        { 
          ACC_cmdID:=queue_ACC.io.deq.bits.cmdID
          ACC_STATE:=wait_ACC_FINISH 
        }
      }
      is(wait_ACC_FINISH)
      {
        ACC_STATE := Mux(!io.busy_acc,set_finish_ACC,ACC_STATE)
      }
      is(set_finish_ACC)
      {
        ACC_qry_port.valid:=true.B
        when(ACC_qry_port.fire)
        {
          ACC_qry_port.bits.is_finish:=true.B
          ACC_STATE:=idle_ACC
        }
      }
    }

    // ******************* Store P **********************
    val idle_ST_P :: fire_ST_P :: wait_ST_P_FINISH :: set_finish_ST_P :: Nil = Enum(4)
    val ST_P_STATE = RegInit(idle_ST_P)
    val ST_cmdID=RegInit(0.U(sysCfg.cmdID_sigLen.W))

    io.store_P_req.valid:=(ST_P_STATE===fire_ST_P)&(!io.busy_store)
    
    queue_STORE_P.io.deq.ready:=(ST_P_STATE===fire_ST_P)&io.store_P_req.fire

    val ST_qry_port=cmd_state_helper.io.query_req(QryTabClient.ST_P)
    ST_qry_port.valid:=false.B
    ST_qry_port.bits.cmdID:=ST_cmdID
    ST_qry_port.bits.is_finish:=false.B
      
    switch(ST_P_STATE)
    {
      is(idle_ST_P)
      {
        when(queue_STORE_P.io.deq.valid)
        { ST_P_STATE:=fire_ST_P  }
      }
      is(fire_ST_P)
      {
        when(io.store_P_req.fire)
        { 
          ST_cmdID:=queue_STORE_P.io.deq.bits.cmdID
          ST_P_STATE:=wait_ST_P_FINISH
        }
      }
      is(wait_ST_P_FINISH)
      {
          ST_P_STATE:=Mux(!io.busy_store,set_finish_ST_P,ST_P_STATE)
      }
      is(set_finish_ST_P)
      {
        ST_qry_port.valid:=true.B
        when(ST_qry_port.fire)
        {
          ST_qry_port.bits.is_finish:=true.B
          ST_P_STATE:=idle_ST_P
        }
      }
    }

    // busy signal
    normal_cmd_sche_free:= (LOAD_L_STATE===idle_LD_L)&(LOAD_R_STATE===idle_LD_R)&(LOAD_P_STATE===idle_LD_P)&
                            (ACC_STATE===idle_ACC)&(ST_P_STATE===idle_ST_P)
    

    // ******************** EXE Finish tracker *********************
    // After EXE completes, update the corresponding flag in the table.
    val begin_matID=sysCfg.first_matID
    val end_matID=sysCfg.last_matID

    val query_mat_ptr=RegInit(begin_matID.U(log2Ceil(sysCfg.totalMatNum).W))

    val mat_busy_rec_choose=WireInit(false.B)

    val query :: get_cmdID :: save_cmdID :: set_finish_exe :: Nil = Enum(4)
    val exe_finish_trace_state = RegInit(query)

    val EXE_Mat_cmdID = RegInit(0.U(sysCfg.cmdID_sigLen.W))

    for(i<-0 until sysCfg.totalMatNum)
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

    val EXE_cmdID_qry_port=cmd_state_helper.io.query_req(QryTabClient.EXE)
    EXE_cmdID_qry_port.valid:= false.B
    EXE_cmdID_qry_port.bits.cmdID:=EXE_Mat_cmdID
    EXE_cmdID_qry_port.bits.is_finish:=true.B

    io.query_mat_req.bits.matID:=query_mat_ptr
    io.query_mat_req.valid:= (exe_finish_trace_state===query)

    switch(exe_finish_trace_state)
    {
        is(query)
        {
            when(io.query_mat_req.fire)
            {
                query_mat_ptr:=Mux(no_change||start,Mux(query_mat_ptr===end_matID.U,begin_matID.U,query_mat_ptr+1.U),query_mat_ptr)
                exe_finish_trace_state:=Mux(no_change||start,exe_finish_trace_state,get_cmdID)
                for(i<-0 until sysCfg.totalMatNum)
                {
                    when(query_mat_ptr===i.U & !no_change)
                    {
                        mat_busy_rec_vec(i):= !mat_busy_rec_vec(i)
                    }
                }
            }
        }
        is(get_cmdID)
        {
          when(mat_cmdID_writeEn_wire===false.B)
          {
            mat_cmdID_en_wire:=true.B
            mat_cmdID_addr_wire:=query_mat_ptr
            exe_finish_trace_state:=save_cmdID
          }
        }
        is(save_cmdID)
        {
          EXE_Mat_cmdID:=mat_cmdID_out_wire
          exe_finish_trace_state:=set_finish_exe
        }
        is(set_finish_exe) // detect finfish
        {
          EXE_cmdID_qry_port.valid:=true.B
          when(EXE_cmdID_qry_port.fire)
          {
            query_mat_ptr:=Mux(query_mat_ptr===end_matID.U,begin_matID.U,query_mat_ptr+1.U)
            exe_finish_trace_state:=query
          }
        }
    }

    // Retrieve the corresponding field from the register.
    def getRegTypeInfo(isaInfo:ISA_Info_new,regType:String):SetRegRule={
      val regFieldInfo=regType match {
            case "setSrc" => isaInfo.setSrcInfo
            case "setDst" => isaInfo.setDstInfo
            case "setSize"    => isaInfo.setSizeInfo
            case "setParams"   => isaInfo.setParamAndRunInfo
            case _         => throw new IllegalArgumentException("Unknown reg field!")
        }
        return regFieldInfo
    }

    def extractRelativeSubField(isaInfo:ISA_Info_new,regType:String,regName:String,sub:String,din:UInt):UInt=
    {
        val regTypeInfo=getRegTypeInfo(isaInfo=isaInfo,regType=regType)
        val regInfo=regTypeInfo.rootField.getField(regName)
        val regSubFieldInfo=regInfo.getField(sub)
        return regSubFieldInfo.extract(din=din,parentPos=(regInfo.end,regInfo.begin))
    }

    def enq_P2SL(regOut:RegsInfo,queue:Queue[P2SL_Req_Full]):Unit ={
      val p2slBits=queue.io.enq.bits.p2sL_req
      val p2slISA=isaInfo.P2SL_info
      
      // Addr
      p2slBits.base_dramAddr_to_load:=extractRelativeSubField(p2slISA,
                                                      regType="setSrc",
                                                      regName="srcAddress",
                                                      sub="memSrcAddr",
                                                      din=regOut.offChipAddrSrc)
      p2slBits.base_picAddr_to_store:=extractRelativeSubField(p2slISA,
                                                      regType="setDst",
                                                      regName="dstAddress",
                                                      sub="onChipDstAddr",
                                                      din=regOut.onChipAddrDst)
      // Size
      p2slBits._L_block_row :=  extractRelativeSubField(p2slISA,
                                                regType="setSize",
                                                regName="row",
                                                sub="matL_Row",
                                                din=regOut.row)
      p2slBits.next_row_offset_elem :=  extractRelativeSubField(p2slISA,
                                                          regType="setSize",
                                                          regName="offset",
                                                          sub="rowOffset",
                                                          din=regOut.offset)
      // Param
      p2slBits.precision:=extractRelativeSubField(p2slISA,
                                          regType="setParams",
                                          regName="others",
                                          sub="bitWidth",
                                          din=regOut.others)

      // cmdID
      queue.io.enq.bits.cmdID:=extractRelativeSubField(p2slISA,
                                          regType="setParams",
                                          regName="cmdID",
                                          sub="cmdID",
                                          din=regOut.cmdID)
    }

    def enq_P2SR(regOut:RegsInfo,queue:Queue[P2SR_Req_Full]):Unit={
      val p2srBits=queue.io.enq.bits.p2sR_req
      val if_T=queue.io.enq.bits.if_T
      val p2srISA=isaInfo.P2SR_info

      // Addr
      p2srBits.dramAddr:=extractRelativeSubField(p2srISA,
                                         regType="setSrc",
                                         regName="srcAddress",
                                         sub="memSrcAddr",
                                          din=regOut.offChipAddrSrc)
      p2srBits.base_arrayID_to_store:=extractRelativeSubField(p2srISA,
                                                      regType="setDst",
                                                      regName="dstAddress",
                                                      sub="onChipDstAddr",
                                                      din=regOut.onChipAddrDst)
      // Size
      p2srBits.next_row_offset_bytes:=extractRelativeSubField(
                                      p2srISA,
                                      regType="setSize",
                                      regName="offset",
                                      sub="rowOffset",
                                      din=regOut.offset)
      p2srBits.nRows:=extractRelativeSubField(p2srISA,
                                      regType="setSize",
                                      regName="row",
                                      sub="matR_Row",
                                      din=regOut.row)
      p2srBits.nCols:=extractRelativeSubField(p2srISA,
                                      regType="setSize",
                                      regName="bytePerRow",
                                      sub="matR_Col",
                                      din=regOut.bytePerRow)
      
      // Param
      p2srBits.bufNum:=extractRelativeSubField(p2srISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="nBufPerMat",
                                      din=regOut.others)
      
      p2srBits.precision:=extractRelativeSubField(p2srISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="bitWidth",
                                      din=regOut.others)
    
      if_T:=extractRelativeSubField(p2srISA,
                            regType="setParams",
                            regName="others",
                            sub="if_T",
                            din=regOut.others)

      // cmdID
      queue.io.enq.bits.cmdID:=extractRelativeSubField(p2srISA,
                                                      regType="setParams",
                                                      regName="cmdID",
                                                      sub="cmdID",
                                                      din=regOut.cmdID)
    }

    def enq_LOAD(regOut:RegsInfo,queue:Queue[LD_P_Req_Full]):Unit={
      val loadBits=queue.io.enq.bits.ld_P_req
      val loadISA=isaInfo.LOAD_info

      // Addr
      loadBits.baseAddr_DRAM:=extractRelativeSubField(loadISA,
                              regType="setSrc",
                              regName="srcAddress",
                              sub="memSrcAddr",
                              din=regOut.offChipAddrSrc)
      
      loadBits.baseAddr_Array:=extractRelativeSubField(loadISA,
                                              regType="setDst",
                                              regName="dstAddress",
                                              sub="onChipDstAddr",
                                              din=regOut.onChipAddrDst)
      // Size
      loadBits.byte_per_row :=extractRelativeSubField(
                                      loadISA,
                                      regType="setSize",
                                      regName="bytePerRow",
                                      sub="mat_Col",
                                      din=regOut.bytePerRow)
      loadBits.row          :=extractRelativeSubField(
                                      loadISA,
                                      regType="setSize",
                                      regName="row",
                                      sub="matRow",
                                      din=regOut.row)
      loadBits.offset       :=extractRelativeSubField(
                                      loadISA,
                                      regType="setSize",
                                      regName="offset",
                                      sub="rowOffset",
                                      din=regOut.offset)
      loadBits.padding      :=false.B
      loadBits.padInfo      :=DontCare
      loadBits.dataDir      :=DataDir.bank

      // cmdID
      queue.io.enq.bits.cmdID:=extractRelativeSubField(loadISA,
                                                      regType="setParams",
                                                      regName="cmdID",
                                                      sub="cmdID",
                                                      din=regOut.cmdID)
    }

    def enq_ACC(regOut:RegsInfo,queue:Queue[Accumu_Req_Full]):Unit={
      val accBits=queue.io.enq.bits
      val accISA=isaInfo.ACC_info

      // Addr
      accBits.accReq.base_src_picAddr:=extractRelativeSubField(
                              accISA,
                              regType="setSrc",
                              regName="srcAddress",
                              sub="onChipSrcAddr",
                              din=regOut.onChipAddrSrc)
      accBits.accReq.dest_picAddr:=extractRelativeSubField(
                                            accISA,
                                            regType="setDst",
                                            regName="dstAddress",
                                            sub="onChipDstAddr",
                                            din=regOut.onChipAddrDst)

      // Param
      accBits.accReq.row_num       :=  extractRelativeSubField(
                                      accISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="accRowNum",
                                      din=regOut.others)
      accBits.accReq.src_arrayNum  :=  extractRelativeSubField(
                                      accISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="srcNum",
                                      din=regOut.others)
      accBits.accReq.bitWidth      :=  extractRelativeSubField(
                                      accISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="bitWidth",
                                      din=regOut.others)
                                      
      // cmdID
      queue.io.enq.bits.cmdID:=extractRelativeSubField(accISA,
                                                      regType="setParams",
                                                      regName="cmdID",
                                                      sub="cmdID",
                                                      din=regOut.cmdID)
    }

    def enq_EXE(regOut:RegsInfo,queue:Queue[SetUpIOFull]):Unit={
      val exeBits=queue.io.enq.bits
      val exeISA=isaInfo.EXE_info

      // Addr
      exeBits.setUpIO._L_vec_fetch_addr:=extractRelativeSubField(
                              exeISA,
                              regType="setSrc",
                              regName="srcAddress",
                              sub="baseLAddr",
                              din=regOut.onChipAddrSrc)

      exeBits.setUpAddr:=extractRelativeSubField(
                                         exeISA,
                                         regType="setDst",
                                         regName="dstAddress",
                                         sub="targetMatID",
                                         din=regOut.onChipAddrDst)
      
      // Param
      exeBits.setUpIO._R_block_row        := extractRelativeSubField(
                                                exeISA,
                                                regType="setParams",
                                                regName="others",
                                                sub="R_Valid_nRols",
                                                din=regOut.others)
      exeBits.setUpIO.nBuf                :=  extractRelativeSubField(
                                                exeISA,
                                                regType="setParams",
                                                regName="others",
                                                sub="nBufPerMat",
                                                din=regOut.others)
      exeBits.setUpIO.nCal                :=  extractRelativeSubField(
                                                exeISA,
                                                regType="setParams",
                                                regName="others",
                                                sub="nCalPerMat",
                                                din=regOut.others)
      exeBits.setUpIO._R_base_bit         :=  extractRelativeSubField(
                                                exeISA,
                                                regType="setParams",
                                                regName="others",
                                                sub="Base_R_Bit",
                                                din=regOut.others)
      exeBits.setUpIO._L_precision        :=  extractRelativeSubField(
                                                exeISA,
                                                regType="setParams",
                                                regName="others",
                                                sub="L_Precision",
                                                din=regOut.others)
      exeBits.setUpIO._L_block_row        :=  extractRelativeSubField(
                                                exeISA,
                                                regType="setParams",
                                                regName="others",
                                                sub="L_Block_Row",
                                                din=regOut.others)
      exeBits.setUpIO.signed_L            :=  extractRelativeSubField(
                                                exeISA,
                                                regType="setParams",
                                                regName="others",
                                                sub="SignL",
                                                din=regOut.others)
      exeBits.setUpIO.signed_R_last_exist :=  extractRelativeSubField(
                                                exeISA,
                                                regType="setParams",
                                                regName="others",
                                                sub="SignR_bitLast",
                                                din=regOut.others)
      exeBits.setUpIO.accWidth            :=  extractRelativeSubField(
                                                exeISA,
                                                regType="setParams",
                                                regName="others",
                                                sub="accWidth",
                                                din=regOut.others)

      exeBits.setUpIO.exec                :=  DontCare

      // cmdID
      queue.io.enq.bits.cmdID:=extractRelativeSubField(exeISA,
                                                      regType="setParams",
                                                      regName="cmdID",
                                                      sub="cmdID",
                                                      din=regOut.cmdID)
    }

    def enq_STORE(regOut:RegsInfo,queue:Queue[ST_P_Req_Full]):Unit={
      val storeBits=queue.io.enq.bits.st_P_req
      val storeISA=isaInfo.STORE_info

      // Addr
      storeBits.baseAddr_Array  :=  extractRelativeSubField(
                                    storeISA,
                                    regType="setSrc",
                                    regName="srcAddress",
                                    sub="onChipSrcAddr",din=regOut.onChipAddrSrc)

      storeBits.baseAddr_DRAM   :=  extractRelativeSubField(
                                    storeISA,
                                    regType="setDst",
                                    regName="dstAddress",
                                    sub="memDstAddr",din=regOut.offChipAddrDst)
      
      // Size
      storeBits.byte_per_row    :=  extractRelativeSubField(
                                      storeISA,
                                      regType="setSize",
                                      regName="bytePerRow",
                                      sub="matCol",din=regOut.bytePerRow)
      storeBits.row             :=  extractRelativeSubField(
                                      storeISA,
                                      regType="setSize",
                                      regName="row",
                                      sub="matRow",din=regOut.row)
      storeBits.offset          :=  extractRelativeSubField(
                                      storeISA,
                                      regType="setSize",
                                      regName="offset",
                                      sub="rowOffset",din=regOut.offset)
      // cmdID
      queue.io.enq.bits.cmdID:=extractRelativeSubField(storeISA,
                                                      regType="setParams",
                                                      regName="cmdID",
                                                      sub="cmdID",
                                                      din=regOut.cmdID)
    }

    // The request signal is directly connected to the register.
    def send_SWITCH_req(pic_switch_req: DecoupledIO[SwitchInfo],regOut:RegsInfo) : Unit={
      val switchISA=isaInfo.SWITCH_info

      pic_switch_req.bits.op      :=  extractRelativeSubField(
                                      switchISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="opType",din=regOut.others)
      pic_switch_req.bits.nLevels :=  extractRelativeSubField(
                                      switchISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="nLevels",din=regOut.others)
    }

    // The request signal is directly connected to the register.
    def send_IM2COL_req(im2col_req: DecoupledIO[IN2COL_req],regOut:RegsInfo):Unit={
      val im2colISA=isaInfo.IM2COL_info

      // Addr
      im2col_req.bits.src_baseAddr_DRAM:=extractRelativeSubField(
                                    im2colISA,
                                    regType="setSrc",
                                    regName="srcAddress",
                                    sub="memSrcAddr",din=regOut.offChipAddrSrc)
      im2col_req.bits.dst_baseAddr_DRAM:=extractRelativeSubField(
                                    im2colISA,
                                    regType="setDst",
                                    regName="dstAddress",
                                    sub="memDstAddr",din=regOut.offChipAddrDst)
    

      // Param
      im2col_req.bits.featrueSize  :=  extractRelativeSubField(
                                      im2colISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="featrueSize",din=regOut.others)

      im2col_req.bits.kernalSize   :=  extractRelativeSubField(
                                      im2colISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="kernalSize",din=regOut.others)

      im2col_req.bits.stride       :=  extractRelativeSubField(
                                      im2colISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="strideSize",din=regOut.others)

      im2col_req.bits.padSize      :=  extractRelativeSubField(
                                      im2colISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="nPad",din=regOut.others)

      im2col_req.bits.enWB         :=  extractRelativeSubField(
                                      im2colISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="toCol",din=regOut.others)
    }


    // Check the query type to see if it is querying an immed instruction.
    def is_quey_immed_cmd_func(regOut:RegsInfo):UInt=
    {
      val queryISA=isaInfo.QUERY_info

      val is_immed = extractRelativeSubField(
                                      queryISA,
                                      regType="setParams",
                                      regName="others",
                                      sub="is_immed",din=regOut.others)

      is_immed
    }

  }
}

