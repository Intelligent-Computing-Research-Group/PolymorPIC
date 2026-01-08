package PIC

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._

import sifive.blocks.inclusivecache._

class SwitchCtlIO(sysCfg:Sys_Config,params: InclusiveCacheParameters) extends Bundle
{
    val switch_req=Flipped(Decoupled(new SwitchInfo(sysCfg)))
    val switch_resp=Decoupled(new SwitchResult(sysCfg))

    // If dir can be query
    val isScheNoOtherWorks=Input(Bool())
    val picLevels=Output(UInt(log2Ceil(sysCfg.pic_avail_levels).W))
    // Query dir
    val queryDir= Decoupled(new SwitchDirQuery(params))
    val queryResFromDir=Flipped(Decoupled(new QueryRes(params)))
    val isDirQuerying=Input(Bool())

    // stall other req
    val switchIdle=Output(Bool())

    // Flush part
    val flushReq=Decoupled(new SetTagReq(params))
    val flushDone=Input(Bool())

    // switch info to bank
    val picActivated=Output(Bool())
    val cacheLevelEnd=Output(UInt(log2Ceil(sysCfg.total_levels).W))
} 


abstract class SwitchCtl(sysCfg:Sys_Config,params: InclusiveCacheParameters) extends Module 
{
    val io = IO(new SwitchCtlIO(sysCfg,params))
}