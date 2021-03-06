#
# Copyright (c) Greenplum Inc 2010. All Rights Reserved.
#

"""
"""
import os

from qautils.gppylib.testold.testUtils import *
from qautils.gppylib.gplog import *
from qautils.gppylib.utils import checkNotNone
from qautils.gppylib.system.faultProberInterface import GpFaultProber

logger = get_default_logger()

class GpFaultProberImplForTest(GpFaultProber):
    def __init__(self):
        self.__isPaused = False

    #
    # returns self
    #
    def initializeProber( self, masterPort ) :
        return self

    def pauseFaultProber(self):
        assert not self.__isPaused
        self.__isPaused = True
        testOutput("pausing fault prober")

    def unpauseFaultProber(self):
        assert self.__isPaused
        self.__isPaused = False
        testOutput("unpausing fault prober")

    def isFaultProberPaused(self):
        return self.__isPaused
    
    def getFaultProberInterval(self):
        return 60
