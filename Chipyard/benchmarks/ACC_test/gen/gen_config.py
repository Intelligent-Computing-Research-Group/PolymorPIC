#  Copyright (c) 2025 ICRG@Shanghai Jiao Tong University
#  PolymorPIC is licensed under Mulan PSL v2.
#  You can use this software according to the terms and conditions of the Mulan PSL v2.
#  You may obtain a copy of Mulan PSL v2 at:
#           http://license.coscl.org.cn/MulanPSL2
#  THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
#  EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
#  MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
#  See the Mulan PSL v2 for more details.

def write_config(file,test_id,
                    len_64b,
                    srcArrayID,
                    srcNum,
                    destArrayID,
                    bitWidth
                    ):

    file.write(f"\n")
    file.write(f"// ########## testID:{test_id} configurations ############\n")

    file.write(f"\n")
    
    file.write(f'int {test_id}_len_64b = '+str(len_64b)+';\n')
    file.write(f'int {test_id}_srcArrayID = '+str(srcArrayID)+';\n')
    file.write(f'int {test_id}_srcNum = '+str(srcNum)+';\n')
    file.write(f'int {test_id}_destArrayID = '+str(destArrayID)+';\n')
    file.write(f'bool {test_id}_bitWidth = '+str(bitWidth)+';\n')
  
    file.write(f"\n")