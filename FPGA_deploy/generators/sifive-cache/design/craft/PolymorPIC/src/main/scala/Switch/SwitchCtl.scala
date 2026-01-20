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