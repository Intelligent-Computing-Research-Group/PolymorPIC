#  Copyright (c) 2025 ICRG@Shanghai Jiao Tong University
#  PolymorPIC is licensed under Mulan PSL v2.
#  You can use this software according to the terms and conditions of the Mulan PSL v2.
#  You may obtain a copy of Mulan PSL v2 at:
#           http://license.coscl.org.cn/MulanPSL2
#  THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
#  EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
#  MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
#  See the Mulan PSL v2 for more details.

import gen_data
import gen_config
import gen_main
import gen_func

##########################
# Test Configuration
##########################
config_file_name="config.h"
config_file_path="../"+config_file_name
# bankAddrBase points which row
testSet={
        "a1":{"len_64b":512,"srcArrayID":8*4,"srcNum":5,"destArrayID":5*4,"bitWidth":"_32b_"},
        "a2":{"len_64b":16,"srcArrayID":16*4,"srcNum":4,"destArrayID":60*4,"bitWidth":"_32b_"},
        }

#################################
# Read ISA.h getting harware info
#################################
import re
isa_file_name="../ISA.h"
with open(isa_file_name, "r") as f:
    content = f.read()
MAT_PER_LEVEL = int(re.search(r"#define\s+MAT_PER_LEVEL\s+(\d+)", content).group(1))
TOTAL_LEVEL = int(re.search(r"#define\s+TOTAL_LEVEL\s+(\d+)", content).group(1))
TOTAL_MAT = MAT_PER_LEVEL*TOTAL_LEVEL
SUB_ARRAY_WORLD_LINE_NUM = int(re.search(r"#define\s+SUB_ARRAY_WORLD_LINE_NUM\s+(\d+)", content).group(1))
print("MAT_PER_LEVEL =", MAT_PER_LEVEL)
print("TOTAL_LEVEL =", TOTAL_LEVEL)
print("TOTAL_LEVEL =", SUB_ARRAY_WORLD_LINE_NUM)

##########################
# write config 
##########################
with open(config_file_path,"w") as file:

    file.write(f"#ifndef {config_file_name.upper().replace('.', '_')}\n")
    file.write(f"#define {config_file_name.upper().replace('.', '_')}\n")

    file.write(f"\n")
    file.write(f"#define _32b_ 1\n")
    file.write(f"#define _16b_ 0\n")
    file.write(f"\n")


    for test in testSet:
        test_info=testSet[test]
        assert test_info["srcNum"]<=8
        assert(test_info["len_64b"]<=2048)
        assert(test_info["srcArrayID"]/4+test_info["srcNum"]<=TOTAL_MAT)
        gen_config.write_config(file,test_id=test,
                            len_64b=test_info["len_64b"],
                            srcArrayID=test_info["srcArrayID"],
                            srcNum=test_info["srcNum"],
                            destArrayID=test_info["destArrayID"],
                            bitWidth=test_info["bitWidth"]
                    )

    file.write('#endif\n')


##########################
# write data
##########################
data_file_name="data.h"
data_file_path="../"+data_file_name
with open(data_file_path,"w") as file:
    file.write(f"#ifndef {data_file_name.upper().replace('.', '_')}\n")
    file.write(f"#define {data_file_name.upper().replace('.', '_')}\n")
    file.write("#include <stdint.h>\n")
    # can have several test

    for test in testSet:
        test_info=testSet[test]
        assert (test_info["bitWidth"]=="_32b_") or (test_info["bitWidth"]=="_16b_")
        gen_data.gen_data_head(
            testID=test,head_file=file,
            len_64b=test_info["len_64b"],
            srcArrayID=test_info["srcArrayID"],
            srcNum=test_info["srcNum"],
            destArrayID=test_info["destArrayID"],
            bitWidth=(32 if test_info["bitWidth"]=="_32b_" else 16),
        )

    file.write('#endif\n')


# #########################
# write main
# #########################
main_file_name="main.c"
main_file_path="../"+main_file_name
with open(main_file_path,"w") as file:
    file.write("#include <stdio.h>\n")
    file.write("#include <string.h>\n")

    file.write("#ifdef LINUX\n")
    file.write("\t#include <sys/mman.h>\n")
    file.write("\t#include <stdlib.h>\n")
    file.write("#endif\n")
    
    file.write("#include \"data.h\"\n")
    file.write("#include \"ISA.h\"\n")
    file.write("#include \"func.h\"\n")
    file.write("#include \"config.h\"\n")
    file.write("\n")
    file.write("int main()\n")
    file.write("{\n")

    file.write(f'''
    #ifdef LINUX
        if (mlockall(MCL_CURRENT | MCL_FUTURE) != 0) 
        {{
            perror("mlockall failed");
            exit(1);
        }}
    #endif
    ''')


    for test in testSet:
        test_info=testSet[test]
        gen_main.test_main_logic_code(file=file,testID=test,testInfo=test_info,
                                      wordline_num=SUB_ARRAY_WORLD_LINE_NUM,
                                    valid=True,printMatrix=False)

    file.write("\n}\n")


##########################
# write func
##########################
func_file_name="func.h"
func_file_path="../"+func_file_name
with open(func_file_path,"w") as head_file:
    # Unde
    head_file.write(f"#ifndef {func_file_name.upper().replace('.', '_')}\n")
    head_file.write(f"#define {func_file_name.upper().replace('.', '_')}\n")

    head_file.write("\n")
    head_file.write("#include <stdio.h>\n")
    head_file.write("#include \"data.h\"\n")
    head_file.write("#include \"config.h\"\n")

    gen_func.wrtie_printBlockedArrayFunc(head_file,16)
    gen_func.wrtie_printBlockedArrayFunc(head_file,32)

    gen_func.write_Validation(head_file,16)
    gen_func.write_Validation(head_file,32)

    head_file.write(f"#endif")
    head_file.close()
