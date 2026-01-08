#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include "rocc.h"

#define ROCC_OPCODE 0

#define ACC_32BIT true
#define ACC_16BIT false


// ----- Mode Switch Information -----
#define ALLOC true
#define FREE false
#define PIC_MODE true
#define MAT_PER_LEVEL 4
#define CACHE_SIZE_KB 1024
#define SUB_ARRAY_WORLD_LINE_NUM 512
#define MAT_SIZE_KB 16
#define TOTAL_LEVEL 16

void setSrcInfo(
	uint64_t srcAddress
);
void setDstInfo(
	uint64_t dstAddress
);
void setSizeInfo(
	uint16_t offset,
	uint16_t bytePerRow,
	uint16_t row
);
uint64_t setParamInfo(
	uint64_t others,
	uint8_t cmdID,
	uint8_t moduleID
);

void LOAD(
	uint64_t memSrcAddr,	// The dram addr of src.
	uint32_t onChipDstAddr,	// The on-chip addr of dst.
	uint16_t matRow,	// Number of row in matrix.
	uint16_t mat_Col,	// The number bytes each row in matrix.
	uint16_t rowOffset,	// The number bytes between row[i][0] and row[i+1][0].
	uint8_t cmdID	// cmdID
);

void P2SL(
	uint64_t memSrcAddr,	// The dram addr of src.
	uint32_t onChipDstAddr,	// he on-chip addr of dst.
	uint8_t matL_Row,	// Number of row in left matrix.
	uint16_t rowOffset,	// The number bytes between row[i][0] and row[i+1][0].
	uint8_t cmdID,	// cmdID
	uint8_t bitWidth	// The valid bit of elements in L. Needs to -1!
);

void P2SR(
	uint64_t memSrcAddr,	// The dram addr of src.
	uint32_t onChipDstAddr,	// he on-chip addr of dst.
	uint16_t matR_Row,	// Number of row in right matrix.
	uint16_t matR_Col,	// Number of col in right matrix.
	uint16_t rowOffset,	// The number bytes between row[i][0] and row[i+1][0].
	uint8_t cmdID,	// cmdID
	uint8_t nBufPerMat,	// Use how many buf in each mat.
	uint8_t bitWidth,	// The valid bit of elements in R. Needs to -1!
	bool if_T	// If transpos.
);

void IM2COL(
	uint64_t memSrcAddr,	// The dram addr of src.
	uint64_t memDstAddr,	// The dram addr of dst.
	uint16_t featrueSize,	// The height/width of feature map before padding.
	uint8_t kernalSize,	// The kernal size.
	uint8_t strideSize,	// The stride size.
	uint8_t nPad,	// The pad size.
	bool toCol	// 是否要写回dram,否则只pad并留在bank上
);

void ACC(
	uint32_t onChipSrcAddr,	// The on-chip addr of dst.
	uint32_t onChipDstAddr,	// The on-chip addr of dst.
	uint8_t cmdID,	// cmdID
	uint8_t srcNum,	// Add how many partial sum. The offset between src is 4 array.
	uint16_t accRowNum,	// The number of row(64b cyrrently) to acc.
	uint8_t bitWidth	// 1bit. The bitWdth of elem. 1->32b,0->16b
);

void EXE(
	uint32_t baseLAddr,	// The base address of L matrix.
	uint8_t targetMatID,	// The target matID.
	uint8_t cmdID,	// cmdID
	uint16_t R_Valid_nRols,	// How many R rols.(In the array, it is the num of valid row of R).
	uint8_t nBufPerMat,	// How many arrays used as buffer to keep P's partial sum.
	uint8_t nCalPerMat,	// How many arrays used to conduct MAC.(The left array except used as buffers will be MAC mode.However, not all of them must work.
	uint8_t Base_R_Bit,	// The first R bit slice's bit position. The left R bit slices are +1 one by one based on it.
	uint8_t L_Precision,	// What is L bitWidth. It should -1. e.g., if it is 8b, write 7 here.
	uint8_t L_Block_Row,	// Number of row in left matrix block.
	bool SignL,	// If L is signed..
	bool SignR_bitLast,	// If R is signed and the highest bit is in this exe.
	bool accWidth,	// The accWidth. 32b->true;16b->false
	bool needL	// This exe need to a new loaded L to begin, meanging the L it needs is not in array.
);

void STORE(
	uint64_t onChipSrcAddr,	// The on-chip addr of src.
	uint64_t memDstAddr,	// The dram addr of dst.
	uint16_t matRow,	// Number of row in matrix.Need to be reduced by one!
	uint16_t matCol,	// The number bytes each row in matrix.
	uint16_t rowOffset,	// The number bytes between row[i][0] and row[i+1][0].
	uint8_t cmdID	// cmdID
);

void SWITCH(
	bool opType,	// Bool(true) is ALLOC, Bool(false) is FREE.
	uint8_t nLevels	// How many clusters of mats to Alloc. Total 15 is available.
);
bool ifSwitchSuccess(uint64_t query_res);
void showSwitchInfo(uint64_t query_res);
void conduct_alloc(uint8_t nLevels);
void conduct_free(uint8_t nLevels);

uint64_t QUERY(
	uint8_t cmdID,	// cmdID
	bool is_immed	// Whether the queried command is immed type command.
);
void waitImmedFree();
void waitEnqCmdFree(uint8_t cmdID);
