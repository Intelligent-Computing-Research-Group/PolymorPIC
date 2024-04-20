package PIC

import chisel3._
import chisel3.util._

// 请求查表的对象
object QryTabClient
{
    val LD_R=0
    val EXE_BEFORE=1
    val EXE_AFTER=2
    val ACC=3
    val ST_P=4
    val LD_P=5
    val INIT=6
    val total_client=7
}

// 请求中包含的附加信息
object OpStates
{
    val BEFORE_RUN =0.U
    val AFTER_RUN =1.U
    val ACC_BEFORE_RUN_QUERY_LD_P =3.U     // 不需要ld p 就是BEFORE_RUN
    val ACC_BEFORE_RUN_EXE =2.U     // ACC query exe
    val ACC_AFTER_RUN_IS_LAST = 4.U // 非is last 就AFTER_RUN
}

// 请求返回的结果类型
object QueryResType
{
    val CAN_RUN = true.B
    val CANNT_RUN = false.B
}

object entryVal
{
    // for load R
    val INVALID=false.B
    val VALID=true.B
    // for exe
    val EXE_NOT_RUN=0.U
    val EXE_RUNNING=1.U
    val EXE_FINISH=2.U
    // for load P
    val LD_P_EMPTY=0.U
    val LD_P_NEED_P=1.U
    val LD_P_HAS_P=2.U
    // for acc
    val ACC_NOT_RUN=false.B
    val ACC_FIRED=true.B    // reset when st finish
    // for store P
    val ST_P_CANNT_RUN=false.B
    val ST_P_CAN_RUN=true.B
}

class StateEntry extends Bundle {
  val LD_R = Bool()
  val EXE = UInt(2.W)
  val LD_P = Vec(4, UInt(2.W))     // 第i个bit表示 第i个分片上是否有P的有效值，即LD_P是否存进去了
  val ACC = Vec(4, Bool())
  val ST_P = Vec(4, Bool())  // 第i个bit表示 第i个分片上是否有P的有效值，即ACC是否存进去了 TODO参数化位数
}

class MatQuery(sysCfg:Sys_Config) extends Bundle
{
    val begin_matID=UInt(log2Ceil(sysCfg.total_valid_mats).W)
    val end_matID=UInt(log2Ceil(sysCfg.total_valid_mats).W)
    val extra_bufID=UInt(log2Ceil(sysCfg.total_array_nums).W)   //精确到某个array
    val state=UInt(3.W)
}

class MatStateTable(sysCfg:Sys_Config,coreCfg:PolymorPIC_Kernal_Config) extends Module
{
    val client_num= QryTabClient.total_client
    val io = IO(new Bundle {
        val query_req=Vec(client_num, Flipped(Decoupled(new MatQuery(sysCfg))))
        val result = Output(Bool())
        val resultEn = Output(Bool())
    })

    io.resultEn:=false.B

    val mat_state_table = SyncReadMem(sysCfg.total_valid_mats,new StateEntry)
    val arbiter = Module(new RRArbiter(new MatQuery(sysCfg), client_num))

    val writeEn_wire=WireInit(false.B)
    val en_wire=WireInit(false.B)
    val addr_wire=WireInit(0.U(log2Ceil(sysCfg.total_valid_mats).W))
    val data_wire=WireInit(0.U.asTypeOf(new StateEntry))

    for(i<-0 until client_num)
    {
        arbiter.io.in(i)<>io.query_req(i)
    }

    val proc_idle :: read_tab_LD_R_BEFORE_RUN :: check_result_LD_R_BEFORE_RUN :: update_tab_LD_R_AFTER_RUN :: read_table_EXE_BEFORE_RUN :: check_result_EXE_BEFORE_RUN :: update_tab_EXE_AFTER_RUN :: read_table_LD_P :: check_result_LD_P :: update_table_LOAD_P_AFTER_RUN :: check_tab_ACC_BEFORE_RUN  :: check_LD_P_OR_IF_LAST_STILL_RUN_BEFORE_ACC_RUN :: mark_LD_P_ACC_NEED_P  :: check_EXE_ACC_BEFORE_RUN :: check_tab_ACC_AFTER_RUN :: update_table_BUF_ACC_AFTER_RUN :: update_table_CMAT_ACC_AFTER_RUN :: check_result_STORE_P_BEFORE_RUN :: update_tab_STORE_P_AFTER_RUN :: init_table :: return_res :: Nil = Enum(21)
    val proc_state = RegInit(proc_idle)

    arbiter.io.out.ready:= (proc_state===proc_idle)

    val check_mat_ptr=RegInit(0.U(log2Ceil(sysCfg.total_valid_mats).W))
    val check_mat_endID=RegInit(0.U(log2Ceil(sysCfg.total_valid_mats).W))
    val query_extra_bufID_arrayAddr=RegInit(0.U(log2Ceil(sysCfg.total_array_nums).W))
    val query_extra_bufID_matID=sysCfg.get_matID_from_arrayID(query_extra_bufID_arrayAddr)
    val query_extra_bufID_inMat_arrayID=sysCfg.get_in_mat_arrayID_from_arrayID(query_extra_bufID_arrayAddr)

    addr_wire:=check_mat_ptr

    // memory read and write logic >>>>>>>>>>>
    when(writeEn_wire & en_wire){
        mat_state_table.write(addr_wire,data_wire)
    }
    
    val table_read_out_wire=mat_state_table.read(addr_wire, en_wire&&(!writeEn_wire))
    // <<<<<<<<<<<<<<<< memory read and write logic

    val result_reg=RegInit(true.B)
    io.result:=result_reg

    val query_state_reg=RegInit(0.U(io.query_req(0).bits.state.getWidth.W))


    switch(proc_state)
    {
        is(proc_idle)
        {
            when(arbiter.io.out.fire)
            {
                check_mat_ptr:=arbiter.io.out.bits.begin_matID
                check_mat_endID:=arbiter.io.out.bits.end_matID
                val state=arbiter.io.out.bits.state
                query_state_reg:=state
                query_extra_bufID_arrayAddr:=arbiter.io.out.bits.extra_bufID
                when(arbiter.io.chosen===QryTabClient.LD_R.U)
                {
                    proc_state:=Mux(state===OpStates.BEFORE_RUN,read_tab_LD_R_BEFORE_RUN,update_tab_LD_R_AFTER_RUN)
                }
                .elsewhen(arbiter.io.chosen===QryTabClient.EXE_BEFORE.U)
                {
                    proc_state:=read_table_EXE_BEFORE_RUN
                }
                .elsewhen(arbiter.io.chosen===QryTabClient.EXE_AFTER.U)
                {
                    proc_state:=update_tab_EXE_AFTER_RUN
                }
                .elsewhen(arbiter.io.chosen===QryTabClient.LD_P.U)
                {
                    proc_state:=read_table_LD_P
                }
                .elsewhen(arbiter.io.chosen===QryTabClient.ACC.U)
                {
                    proc_state:=Mux(state===OpStates.BEFORE_RUN||state===OpStates.ACC_BEFORE_RUN_QUERY_LD_P||state===OpStates.ACC_BEFORE_RUN_EXE,
                                    check_tab_ACC_BEFORE_RUN,check_tab_ACC_AFTER_RUN)
                }
                .elsewhen(arbiter.io.chosen===QryTabClient.ST_P.U)
                {
                    writeEn_wire:=false.B
                    en_wire:=true.B
                    addr_wire:=sysCfg.get_matID_from_arrayID(arbiter.io.out.bits.extra_bufID)
                    proc_state:=Mux(state===OpStates.BEFORE_RUN,check_result_STORE_P_BEFORE_RUN,update_tab_STORE_P_AFTER_RUN)
                }
                .elsewhen(arbiter.io.chosen===QryTabClient.INIT.U)
                {
                    proc_state:=init_table
                }
            }
        }
        // Init table entry
        is(init_table)
        {
            writeEn_wire:=true.B
            en_wire:=true.B
            check_mat_ptr:=check_mat_ptr+1.U

            // write content
            data_wire.LD_R  := entryVal.INVALID
            data_wire.EXE  := entryVal.EXE_NOT_RUN
            data_wire.LD_P.foreach{e=>
                    e:=entryVal.LD_P_EMPTY
            }
            data_wire.ACC.foreach{e=>
                    e:=entryVal.ACC_NOT_RUN
            }
            data_wire.ST_P.foreach{e=>
                    e:=entryVal.ST_P_CANNT_RUN
            }

            proc_state:=Mux(check_mat_ptr===check_mat_endID,return_res,proc_state)
        }

        // LoadR >>>>>>>>>>>>>>>>>>>>>>>>>>>>>> INVALID&VALID
        is(read_tab_LD_R_BEFORE_RUN)
        {
            writeEn_wire:=false.B
            en_wire:=true.B
            proc_state:=check_result_LD_R_BEFORE_RUN
        }
        is(check_result_LD_R_BEFORE_RUN)
        {
            val can_continue = (table_read_out_wire.LD_R===entryVal.INVALID)
            check_mat_ptr:=check_mat_ptr+1.U
            proc_state:=Mux(can_continue,Mux(check_mat_ptr===check_mat_endID,return_res,read_tab_LD_R_BEFORE_RUN),
                                return_res)
            result_reg:=Mux(can_continue,true.B,false.B)
        }
        is(update_tab_LD_R_AFTER_RUN)
        {
            writeEn_wire:=true.B
            en_wire:=true.B
            check_mat_ptr:=check_mat_ptr+1.U

            // write content
            data_wire.LD_R  := entryVal.VALID

            proc_state:=Mux(check_mat_ptr===check_mat_endID,proc_idle,proc_state)
        }
        // <<<<<<<<<<<<<<<<<<<<<<<<<<<< LoadR
        
        // EXE BEFORE RUN  >>>>>>>>>>>>>>>>>>> 
        is(read_table_EXE_BEFORE_RUN)
        {
            writeEn_wire:=false.B
            en_wire:=true.B
            proc_state:=check_result_EXE_BEFORE_RUN
        }
        is(check_result_EXE_BEFORE_RUN)
        {
            val if_LD_R_finish = (table_read_out_wire.LD_R === entryVal.VALID)
            val last_cal_finish = (table_read_out_wire.EXE === entryVal.EXE_NOT_RUN)
            val can_exe = if_LD_R_finish & last_cal_finish
            result_reg:= can_exe
            writeEn_wire:= can_exe
            en_wire:= can_exe

            // write content if can_exe
            data_wire.LD_R  := table_read_out_wire.LD_R
            data_wire.EXE   := entryVal.EXE_RUNNING

            proc_state:=return_res
        }
        // <<<<<<<<<<<<<<<<<<<<<<<<<   EXE BEFORE RUN

        // EXE After run >>>>>>>>>>>>>>>>>>>>>
        is(update_tab_EXE_AFTER_RUN)
        {
            writeEn_wire:=true.B
            en_wire:=true.B

            // write content if can_exe
            data_wire.LD_R  := entryVal.VALID    // LD_R must be valid 否则这次跑完的EXE不可能存在
            data_wire.EXE   := entryVal.EXE_FINISH

            proc_state:=proc_idle
        }
        // <<<<<<<<<<<<<<<<<<<<< EXE After run

        // LOAD_P >>>>>>>>>>>>>>>>>>>>>>
        is(read_table_LD_P)
        {
            writeEn_wire:=false.B
            en_wire:=true.B
            addr_wire:=query_extra_bufID_matID
            proc_state:=Mux(query_state_reg===OpStates.BEFORE_RUN,check_result_LD_P,update_table_LOAD_P_AFTER_RUN)
        }
        is(check_result_LD_P)
        {
            // 不为空，那么之前要用的P还能没有被acc或者store，不能执行
            val arrayID=query_extra_bufID_inMat_arrayID
            val query_res_LD_P=table_read_out_wire.LD_P
            // 检查对应的loadP加载进去了吗
            val need_P= MuxCase(false.B,Seq(
                (arrayID===0.U)->(query_res_LD_P(0)===entryVal.LD_P_NEED_P),
                (arrayID===1.U)->(query_res_LD_P(1)===entryVal.LD_P_NEED_P),
                (arrayID===2.U)->(query_res_LD_P(2)===entryVal.LD_P_NEED_P),
                (arrayID===3.U)->(query_res_LD_P(3)===entryVal.LD_P_NEED_P)
            ))
            result_reg:=need_P
            proc_state:=return_res
        }
        is(update_table_LOAD_P_AFTER_RUN)
        {
            writeEn_wire:=true.B
            en_wire:=true.B
            addr_wire:=query_extra_bufID_matID

            val in_mat_arrayID=query_extra_bufID_inMat_arrayID

            // 表明P已经被加载
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.LD_P(arrayPtr):=Mux(in_mat_arrayID===arrayPtr.U,entryVal.LD_P_HAS_P,table_read_out_wire.LD_P(arrayPtr))
            }

            // ACC这里只是原样存回
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.ACC(arrayPtr):=table_read_out_wire.ACC(arrayPtr)
            }

            // ST_P这里只是原样存回
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.ST_P(arrayPtr):=table_read_out_wire.ST_P(arrayPtr)
            }
            proc_state:=proc_idle
        }
        // <<<<<<<<<<<<<<<<<<<<< LOAD_P After run

        // ACC >>>>>>>>>>>>>
        is(check_tab_ACC_BEFORE_RUN)
        {
            writeEn_wire:=false.B
            en_wire:=true.B
            addr_wire:=Mux(query_state_reg===OpStates.ACC_BEFORE_RUN_EXE,
                                check_mat_ptr,
                            query_extra_bufID_matID)

            proc_state:=Mux(query_state_reg===OpStates.ACC_BEFORE_RUN_EXE,
                                check_EXE_ACC_BEFORE_RUN,
                               check_LD_P_OR_IF_LAST_STILL_RUN_BEFORE_ACC_RUN)
        }
        is(check_LD_P_OR_IF_LAST_STILL_RUN_BEFORE_ACC_RUN)
        {
            val arrayID=query_extra_bufID_inMat_arrayID
            val query_res_LD_P=table_read_out_wire.LD_P
            // 检查对应的loadP加载进去了吗
            val can_continue_LD_P= MuxCase(false.B,Seq(
                (arrayID===0.U)->(query_res_LD_P(0)===entryVal.LD_P_HAS_P),
                (arrayID===1.U)->(query_res_LD_P(1)===entryVal.LD_P_HAS_P),
                (arrayID===2.U)->(query_res_LD_P(2)===entryVal.LD_P_HAS_P),
                (arrayID===3.U)->(query_res_LD_P(3)===entryVal.LD_P_HAS_P)
            ))

            // 检查之前的ACC还没完事吗？ACC完成后释放R，新的R加载进来后，计算完成后会触发ACC，但是此时之前的ACC结果可能还没有STORE
            val query_res_ACC=table_read_out_wire.ACC
            val can_continue_LAST_STILL=  MuxCase(false.B,Seq(
                (arrayID===0.U)->(query_res_ACC(0)===entryVal.ACC_NOT_RUN),
                (arrayID===1.U)->(query_res_ACC(1)===entryVal.ACC_NOT_RUN),
                (arrayID===2.U)->(query_res_ACC(2)===entryVal.ACC_NOT_RUN),
                (arrayID===3.U)->(query_res_ACC(3)===entryVal.ACC_NOT_RUN)
            ))

            val can_continue=Mux(query_state_reg===OpStates.ACC_BEFORE_RUN_QUERY_LD_P,
                                    can_continue_LD_P&can_continue_LAST_STILL,
                                    can_continue_LAST_STILL)

            val need_infrom_LD_P= can_continue_LAST_STILL&(!can_continue_LD_P)&(query_state_reg===OpStates.ACC_BEFORE_RUN_QUERY_LD_P)

            result_reg:=Mux(can_continue,true.B,        // can run
                               false.B)            // cannt run

            proc_state:=Mux(need_infrom_LD_P,mark_LD_P_ACC_NEED_P,return_res)
        }
        is(mark_LD_P_ACC_NEED_P)
        {
            writeEn_wire:=true.B
            en_wire:=true.B
            addr_wire:=query_extra_bufID_matID

            val in_mat_arrayID=query_extra_bufID_inMat_arrayID

            // 设置需要loadP
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.LD_P(arrayPtr):=Mux(in_mat_arrayID===arrayPtr.U,entryVal.LD_P_NEED_P,table_read_out_wire.LD_P(arrayPtr))
            }

            // 不变
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.ST_P(arrayPtr):=table_read_out_wire.ST_P(arrayPtr)
            }

            // ACC不变
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.ACC(arrayPtr):=table_read_out_wire.ACC(arrayPtr)
            }

            proc_state:=return_res

        }
        is(check_EXE_ACC_BEFORE_RUN)
        {
            val can_continue= (table_read_out_wire.EXE===entryVal.EXE_FINISH)
            check_mat_ptr:=check_mat_ptr+1.U
            proc_state:=Mux(can_continue,Mux(check_mat_ptr===check_mat_endID,return_res,check_EXE_ACC_BEFORE_RUN),
                                return_res)

            result_reg:=Mux(can_continue,true.B,false.B)
        }
        // 因为每个mat存着多个不同的P，要先读出里面的数据
        is(check_tab_ACC_AFTER_RUN)
        {
            writeEn_wire:=false.B
            en_wire:=true.B
            addr_wire:=sysCfg.get_matID_from_arrayID(query_extra_bufID_arrayAddr)
            proc_state:=update_table_BUF_ACC_AFTER_RUN
        }
        // 更新可以store,ACC FIRED
        is(update_table_BUF_ACC_AFTER_RUN)
        {
            addr_wire:=sysCfg.get_matID_from_arrayID(query_extra_bufID_arrayAddr)
            writeEn_wire:=true.B
            en_wire:=true.B

            val in_mat_arrayID=query_extra_bufID_inMat_arrayID

            // 声明可以store
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.ST_P(arrayPtr):=Mux(in_mat_arrayID===arrayPtr.U,entryVal.ST_P_CAN_RUN,table_read_out_wire.ST_P(arrayPtr))
            }

            // ACC 无效的标志将由store去除
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.ACC(arrayPtr):=Mux(in_mat_arrayID===arrayPtr.U,entryVal.ACC_FIRED,table_read_out_wire.ACC(arrayPtr))
            }

            // load_p 无效的标志由store去除，所以这里只是原样存回
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.LD_P(arrayPtr):=table_read_out_wire.LD_P(arrayPtr)
            }

            proc_state:=update_table_CMAT_ACC_AFTER_RUN
        }
        // 更新exe 根据是否last判断是否可以继续exe或reset ld_R
        is(update_table_CMAT_ACC_AFTER_RUN)
        {
            writeEn_wire:=true.B
            en_wire:=true.B
            check_mat_ptr:=check_mat_ptr+1.U

            data_wire.LD_R:=Mux(query_state_reg===OpStates.ACC_AFTER_RUN_IS_LAST,entryVal.INVALID,
                                                    entryVal.VALID)
            data_wire.EXE:=entryVal.EXE_NOT_RUN

            proc_state:=Mux(check_mat_ptr===check_mat_endID,proc_idle,proc_state)
        }
        // <<<<<<<<< ACC

        // Store_P >>>>>>>>>>>>>>>>>>>>>
        is(check_result_STORE_P_BEFORE_RUN)
        {
            val query_res=table_read_out_wire.ST_P
            val arrayID=sysCfg.get_in_mat_arrayID_from_arrayID(query_extra_bufID_arrayAddr)
            val can_continue= MuxCase(entryVal.ST_P_CANNT_RUN,Seq(
                (arrayID===0.U)->(query_res(0)===entryVal.ST_P_CAN_RUN),
                (arrayID===1.U)->(query_res(1)===entryVal.ST_P_CAN_RUN),
                (arrayID===2.U)->(query_res(2)===entryVal.ST_P_CAN_RUN),
                (arrayID===3.U)->(query_res(3)===entryVal.ST_P_CAN_RUN)
            ))
            result_reg:=can_continue
            proc_state:=return_res
        }
        is(update_tab_STORE_P_AFTER_RUN)
        {
            writeEn_wire:=true.B
            en_wire:=true.B
            addr_wire:=sysCfg.get_matID_from_arrayID(query_extra_bufID_arrayAddr)
            
            val in_mat_arrayID=query_extra_bufID_inMat_arrayID

            // 将load_p设为empty
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.LD_P(arrayPtr):=Mux(in_mat_arrayID===arrayPtr.U,entryVal.LD_P_EMPTY,table_read_out_wire.LD_P(arrayPtr))
            }

            // acc设为not run
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.ACC(arrayPtr):=Mux(in_mat_arrayID===arrayPtr.U,entryVal.ACC_NOT_RUN,table_read_out_wire.ACC(arrayPtr))
            }
            
            // STORE_P设为Cannt run
            for(arrayPtr <- 0 until coreCfg.arrayNums)
            {
                data_wire.ST_P(arrayPtr):=Mux(in_mat_arrayID===arrayPtr.U,entryVal.ST_P_CANNT_RUN,
                                                table_read_out_wire.ST_P(arrayPtr))
            }

            proc_state:=proc_idle
        }

        is(return_res)
        {
            io.resultEn:=true.B
            proc_state:=proc_idle
        }
    }


}