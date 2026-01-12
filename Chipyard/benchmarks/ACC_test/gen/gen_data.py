#  Copyright (c) 2025 ICRG@Shanghai Jiao Tong University
#  PolymorPIC is licensed under Mulan PSL v2.
#  You can use this software according to the terms and conditions of the Mulan PSL v2.
#  You may obtain a copy of Mulan PSL v2 at:
#           http://license.coscl.org.cn/MulanPSL2
#  THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
#  EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
#  MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
#  See the Mulan PSL v2 for more details.

import torch
from torch import nn
import numpy as np

def regular_matrix(row, col,bitWidth):
    max_val=2**bitWidth
    arr_1d = np.arange(row * col) % max_val
    arr_2d = arr_1d.reshape((row, col))
    return arr_2d

def write_matrix(f,matrix_name,matrix_type, matrix):
    f.write('\n')
    f.write(matrix_type+" "+matrix_name+'['+str(matrix.size)+'] = {\n')
    for i in range(matrix.shape[0]):
        for j in range(matrix.shape[1]):
            f.write(f"0x{matrix[i,j]:02x}")
            if i==matrix.shape[0]-1 and j==matrix.shape[1]-1:
                f.write('\n};\n')
            else:
                f.write(',')
        f.write('\n')
    f.write('\n')

def write_matrix_blocks(f,matrix_type,matrix_name,blocked_matrix_list):
    f.write('\n')
    combined_array = np.concatenate(blocked_matrix_list)
    block_elem = blocked_matrix_list[0].shape[0]*blocked_matrix_list[0].shape[1]
    for index, block in enumerate(blocked_matrix_list):
        f.write(f'// BitSlice {index}\n')
        f.write(f"{matrix_type} {matrix_name}_bit{index}[{block_elem}] = {{\n")
        for row in range(block.shape[0]):
            for col in range(block.shape[1]):
                end=(row==block.shape[0]-1)and(col==block.shape[1]-1)
                f.write(f"0x{block[row,col]:08x}")
                if end:
                    f.write('\n};\n')
                else:
                    f.write(f",")
            f.write('\n')
    f.write('\n')

def split_matrix(L_block,precision):
    split_array_list=[]
    contact_cols=int(L_block.shape[1]/8) if L_block.shape[1]%8==0 else int(L_block.shape[1]/8)+1

    for i in range(precision):
        bitSlice = np.bitwise_and(L_block,2**i)
        bitSlice = np.right_shift(bitSlice,i)
        # padding
        if bitSlice.shape[1] < 64:
            bitSlice = np.pad(bitSlice, ((0, 0), (0, 64 - L_block.shape[1])), mode='constant', constant_values=0)
        # combine it to 64b unsigned long
        bitSliceCombinedArray=np.zeros((bitSlice.shape[0],1),dtype=np.uint64)
        for j in range(bitSlice.shape[0]):
            now = 0
            for k in range(bitSlice.shape[1]):
                now = now + (bitSlice[j,k]<<k)
                if k==(bitSlice.shape[1]-1):
                    bitSliceCombinedArray[j][0]=now
                    now = 0
        split_array_list.append(bitSliceCombinedArray)

    return split_array_list

def get_dType_str(bitWidth=8):
    dtype_str=""
    if bitWidth==8:
        dtype_str="char"
    elif bitWidth==16:
        dtype_str="uint16_t"
    elif bitWidth==32:
        dtype_str="uint32_t"
    elif bitWidth==64:
        dtype_str="unsigned long"
    return dtype_str

def gen_data_head(
                testID,head_file,
                len_64b,
                srcArrayID,
                srcNum,
                destArrayID,
                bitWidth
                ):
    # Config
    head_file.write(f"\n")
    # head_file.write(f'#define DATA_TYPE {get_dType_str(bitWidth)}\n\n')

    # Data preparation SRC
    num_per_row=int(64/bitWidth)
    src_mat_list=[]
    for i in range(srcNum):
        mat = np.random.randint(0,2**bitWidth,(len_64b,num_per_row),dtype=(np.uint32 if bitWidth==32 else np.uint16))
        src_mat_list.append(mat)
        write_matrix(head_file,f"{testID}_srcMat_{i}",get_dType_str(bitWidth),mat)

    # Data preparation Result
    accu_mat=np.sum(src_mat_list, axis=0)
    accu_mat=accu_mat&((2**bitWidth)-1)
    write_matrix(head_file,f"{testID}_resMatTrue",get_dType_str(bitWidth),accu_mat)

    head_file.write(f"{get_dType_str(bitWidth)} {testID}_resMatOut[{num_per_row*len_64b}];\n")


    # array info list
    head_file.write(f"uint16_t {testID}_srcArrayID_list[{srcNum}] = {{")
    for i in range(srcNum):
        head_file.write(f"{srcArrayID+i*4}")
        if i!=(srcNum-1):
            head_file.write(",")
    head_file.write("};\n")

    head_file.write(f"{get_dType_str(bitWidth)}* {testID}_srcArray_content[{srcNum}] = {{")
    for i in range(srcNum):
        head_file.write(f"{testID}_srcMat_{i}")
        if i!=(srcNum-1):
            head_file.write(",")
    head_file.write("};\n")





    head_file.write(f"\n")