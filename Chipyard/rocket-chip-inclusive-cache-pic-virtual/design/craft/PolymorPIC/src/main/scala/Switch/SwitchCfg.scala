package PIC

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._


// res and rep with cmd res
class SwitchInfo(sysCfg:Sys_Config) extends Bundle
{
    val op=Bool()
    val nLevels=UInt(log2Ceil(sysCfg.pic_avail_levels).W)
}

class SwitchResult(sysCfg:Sys_Config) extends Bundle
{
    val op_success=Bool()
    val avail_MatID_begin=UInt(log2Ceil(sysCfg.totalMatNum).W)
}

import sifive.blocks.inclusivecache._
// communication with Dir
class SwitchDirQuery(params: InclusiveCacheParameters) extends Bundle
{
    val setID = UInt((params.setBits).W)
    val beginWayID = UInt((params.wayBits).W)
    val endWayID= UInt((params.wayBits).W)
}

class QueryRes(params: InclusiveCacheParameters) extends Bundle
{
    val tagID = UInt((params.tagBits).W)
}