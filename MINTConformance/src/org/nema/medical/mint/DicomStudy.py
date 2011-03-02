#!/usr/bin/python
# -----------------------------------------------------------------------------
# $Id$
#
# Copyright (C) 2010 MINT Working group. All rights reserved.
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# Contact mint-user@googlegroups.com if any conditions of this
# licensing are not clear to you.
# -----------------------------------------------------------------------------

import getopt
import glob
import os
import string
import sys
import traceback

from os.path import join

from org.nema.medical.mint.DataDictionary import DataDictionary
from org.nema.medical.mint.DicomInstance  import DicomInstance

# -----------------------------------------------------------------------------
# DicomStudy
# -----------------------------------------------------------------------------
class DicomStudy():
   def __init__(self, dcmDir, dataDictionaryUrl):
   
       if not os.path.isdir(dcmDir):
          raise IOError("Directory does not exist - "+dcmDir)
          
       self.__dcmDir = dcmDir
       self.__instances = []
       self.__instancesByUID = {}
       self.__dataDictionary = DataDictionary(dataDictionaryUrl)
       self.__output = None
       self.__read()

   def setOutput(self, output):
       if output == "": return
       if self.__output != None: self.__output(close)
       if os.access(output, os.F_OK):
          raise IOError("File already exists - "+output)
       self.__output = open(output, "w")
       
   def tidy(self):
       """
       Removes tempory binary items.
       """
       for instance in self.__instances: instance.tidy()
       if self.__output != None: self.__output.close()

   def studyInstanceUID(self):
       if self.numInstances() > 0:
          return self.instance(0).studyInstanceUID()
       else:
          return ""
       
   def numInstances(self):
       return len(self.__instances)
       
   def instance(self, n):
       return self.__instances[n]
       
   def instanceByUID(self, sopInstanceUID):
       return self.__instancesByUID[sopInstanceUID]
       
   def debug(self):
       if self.__output == None:
          print "> Study", self.studyInstanceUID()
       else:
          self.__output.write("> Study "+self.studyInstanceUID()+"\n")
       numInstances = self.numInstances()
       for n in range(0, numInstances):
           instance = self.instance(n)
           instance.debug(self.__output)
       
   def __read(self):

       dcmNames = []
       for root, dirs, files in os.walk(self.__dcmDir, topdown=False):
           for name in files:
               filename = join(root, name)
               if DicomInstance.isDicom(filename):
                  dcmNames.append(filename)

       for dcmName in dcmNames:
           instance = DicomInstance(dcmName, self.__dataDictionary)
           self.__instances.append(instance)
           self.__instancesByUID[instance.sopInstanceUID()] = instance
           
# -----------------------------------------------------------------------------
# main
# -----------------------------------------------------------------------------
def main():
    progName = sys.argv[0]
    (options, args)=getopt.getopt(sys.argv[1:], "d:o:h")
    
    # ---
    # Check for data dictionary.
    # ---
    dataDictionaryUrl = DataDictionary.DCM4CHE_URL
    for opt in options:
        if opt[0] == "-d":
           dataDictionaryUrl = opt[1]
           
    # ---
    # Check for output option.
    # ---
    output = ""
    for opt in options:
        if opt[0] == "-o":
           output = opt[1]
           
    # ---
    # Check for help option.
    # ---
    help = False
    for opt in options:
        if opt[0] == "-h":
           help = True
           
    try:
       if help or len(args) < 1:

          print "Usage", progName, "[options] <dicom_dir>"
          print "  -d <data_dictionary_url>: defaults to DCM4CHE"
	  print "  -o <output>:              output filename (defaults to stdout)"
	  print "  -h:                       displays usage"
          sys.exit(1)
          
       # ---
       # Read dicom.
       # ---
       dcmDir = args[0];
       study = DicomStudy(dcmDir, dataDictionaryUrl)
       study.setOutput(output)
       study.debug()
       study.tidy()
       
    except Exception, exception:
       traceback.print_exception(sys.exc_info()[0], 
                                 sys.exc_info()[1],
                                 sys.exc_info()[2])
       sys.exit(1)
       
if __name__ == "__main__":
   main()
