/*******************************************************************************
 * This program and the accompanying materials
 * are made available under the terms of the MIT License available at
 * https://opensource.org/licenses/MIT
 *******************************************************************************/

package com.net2plan.niw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.IntStream;

import com.net2plan.interfaces.networkDesign.Net2PlanException;
import com.net2plan.utils.Pair;
import com.net2plan.utils.Quadruple;


/** This class is used to account for the occupation of the optical spectrum in the network.  
 * The object can be created from an existing network. To make a valid optical design, the user is responsible of 
 * using this object methods to check if routing and spectrum assignments (RSAs) of new lightpaths are valid. Also, this object 
 * includes some methods for simple RSA recommendations, e.g. first-fit assignments
 * (occupy idle and valid resources)
 * Occupation is represented by optical slots, each defined by an integer. The central frequency of optical slot i is 193.1+i*0.0125 THz.
 * All optical slots are supposed to have the same width 12.5 GHz (see WNetConstants)
 *
 */
public class OpticalSimulationModule
{
	public static class LpSignalState
	{
		private double power_dbm;
		private double cd_psPerNm;
		private double pmdSquared_ps2;
		private double osnrAt12_5GhzRefBw;
		public LpSignalState(double power_dbm, double cd_psPerNm, double pmdSquared_ps2, double osnrAt12_5GhzRefBw) {
			super();
			this.power_dbm = power_dbm;
			this.cd_psPerNm = cd_psPerNm;
			this.pmdSquared_ps2 = pmdSquared_ps2;
			this.osnrAt12_5GhzRefBw = osnrAt12_5GhzRefBw;
		}
		public LpSignalState() {
			super();
			this.power_dbm = -1;
			this.cd_psPerNm = -1;
			this.pmdSquared_ps2 = -1;
			this.osnrAt12_5GhzRefBw = -1;
		}
		public double getPower_dbm() {
			return power_dbm;
		}
		public double getCd_psPerNm() {
			return cd_psPerNm;
		}
		public double getPmdSquared_ps2() {
			return pmdSquared_ps2;
		}
		public double getOsnrAt12_5GhzRefBw() {
			return osnrAt12_5GhzRefBw;
		}
		public void setPower_dbm(double power_dbm) {
			this.power_dbm = power_dbm;
		}
		public void setCd_psPerNm(double cd_psPerNm) {
			this.cd_psPerNm = cd_psPerNm;
		}
		public void setPmdSquared_ps2(double pmdSquared_ps2) {
			this.pmdSquared_ps2 = pmdSquared_ps2;
		}
		public void setOsnrAt12_5GhzRefBw(double osnrAt12_5GhzRefBw) {
			this.osnrAt12_5GhzRefBw = osnrAt12_5GhzRefBw;
		}
		public LpSignalState getCopy () { return new LpSignalState(power_dbm, cd_psPerNm, pmdSquared_ps2, osnrAt12_5GhzRefBw); }
	}
	
	/** speed of light in m/s */
   public final static double constant_c = 299792458; 
   /** Planck constant m^2 kg/sec */
   public final static double constant_h = 6.62607004E-34; 
	private final WNet wNet;
	final private SortedMap<WLightpath,LpSignalState> perLpPerMetric_valAtDropTransponderEnd = new TreeMap<> ();
	final private SortedMap<WFiber,SortedMap<WLightpath,Pair<LpSignalState,LpSignalState>>> perFiberPerLpPerMetric_valStartEnd = new TreeMap<> ();
	final private SortedMap<WFiber,Quadruple<Double,Double,List<Double>,List<Double>>> perFiberTotalPower_valStartEndAndAtEachOlaInputOutput = new TreeMap<> ();
	final private SortedMap<WFiber,SortedMap<WLightpath,SortedMap<Integer , Pair<LpSignalState,LpSignalState>>>> perFiberPerLpPerOlaIndexMetric_valStartEnd = new TreeMap<> ();
	
	public OpticalSimulationModule (WNet wNet) 
	{
		this.wNet = wNet;
		this.updateAllPerformanceInfo();
	}
	
    public static double osnrInDbUnitsAccummulation_dB (List<Double> osnrs_dB)
    {
        if (osnrs_dB.size () == 0) return Double.MAX_VALUE;
        double resDenom_linear = 0; for (double osnr_dB : osnrs_dB) { if (osnr_dB == Double.MAX_VALUE) continue; resDenom_linear += 1.0 / dB2linear(osnr_dB); }
        return resDenom_linear == 0? Double.MIN_VALUE : linear2dB(1.0 / resDenom_linear);
    }
    public static double dB2linear(double dB)
    {
        return Math.pow(10, dB / 10);
    }
    public static double linear2dB(double num)
    {
        return num == 0? -Double.MAX_VALUE : 10 * Math.log10(num);
    }
    public static double osnrContributionEdfaRefBw12dot5GHz_linear (double centralFrequencyChannel_Hz , double noiseFactor_dB, double inputPowerPerChannel_dBm)
    {
    	if (noiseFactor_dB == -Double.MAX_VALUE) return Double.MAX_VALUE;
        final double inputPower_W = dB2linear(inputPowerPerChannel_dBm) * 1E-3;
        final double edfa_NF_linear = dB2linear(noiseFactor_dB);
        final double referenceBandwidthAtHighestFrequency_Hz = 12.5 * 1e9;
        final double thisEDFAAddedNoise_W = edfa_NF_linear * constant_h * centralFrequencyChannel_Hz * referenceBandwidthAtHighestFrequency_Hz;
        final double addedOSNRThisOA_linear = inputPower_W / thisEDFAAddedNoise_W;
        return addedOSNRThisOA_linear;
    }

    public OpticalSimulationModule updateAllPerformanceInfo ()
    {
    	System.out.println("Update all performance info");
   	 for (WFiber e : wNet.getFibers())
   	 {
   		 perFiberPerLpPerMetric_valStartEnd.put(e, new TreeMap<> ());
   		 perFiberPerLpPerOlaIndexMetric_valStartEnd.put(e, new TreeMap <> ());
   		 for (WLightpath lp : e.getTraversingLps())
   			perFiberPerLpPerOlaIndexMetric_valStartEnd.get(e).put(lp, new TreeMap<> ());
   	 }
   	 for (WLightpath lp : wNet.getLightpaths())
   	 {
   		 final int numOpticalSlots = lp.getOpticalSlotIds().size();
   		 final double centralFrequency_hz = 1e12 * lp.getCentralFrequencyThz();
   		 Optional<Pair<LpSignalState,LpSignalState>> previousFiberInfo = Optional.empty();
   		 final List<WFiber> lpSeqFibers = lp.getSeqFibers();
   		 for (int contFiber = 0; contFiber < lpSeqFibers.size() ; contFiber ++)
   		 {
   			 final WFiber fiber = lpSeqFibers.get(contFiber);
   	   		 final boolean firstFiber = contFiber == 0;
   			 final Pair<LpSignalState,LpSignalState> infoToAdd = Pair.of(new LpSignalState(), new LpSignalState());
   			 final SortedMap<Integer , Pair<LpSignalState,LpSignalState>> infoToAddPerOla = new TreeMap<> ();
   			 perFiberPerLpPerMetric_valStartEnd.get(fiber).put(lp, infoToAdd);
   			 perFiberPerLpPerOlaIndexMetric_valStartEnd.get(fiber).put(lp, infoToAddPerOla);
   			
   			 final IOadmArchitecture oadm_a = fiber.getA().getOpticalSwitchingArchitecture();
   			 final double totalJustFiberGain_dB = fiber.getOlaGains_dB().stream().mapToDouble(e->e).sum();
   			 final double totalJustFiberAttenuation_dB = fiber.getAttenuationCoefficient_dbPerKm() * fiber.getLengthInKm();
   			 final double totalJustFiberCdBalance_psPerNm = fiber.getChromaticDispersionCoeff_psPerNmKm() * fiber.getLengthInKm() + fiber.getOlaCdCompensation_psPerNm().stream().mapToDouble(e->e).sum();
   			 final double totalJustFiberPmdSquaredBalance_ps2 = fiber.getLengthInKm() * Math.pow(fiber.getPmdLinkDesignValueCoeff_psPerSqrtKm() , 2) + fiber.getOlaPmd_ps().stream().mapToDouble(e->Math.pow(e, 2)).sum();
   			 final LpSignalState state_startFiberBeforeBooster;
   			 final WFiber previousFiber = contFiber == 0? null : lpSeqFibers.get(contFiber-1);
   			 if (firstFiber)
   				state_startFiberBeforeBooster = oadm_a.getOutLpStateForAddedLp(new LpSignalState(lp.getAddTransponderInjectionPower_dBm() , 0.0, 0.0, Double.MAX_VALUE), lp.getAddModuleIndexInOrigin(), lp.getSeqFibers().get(0));
   			 else
   				state_startFiberBeforeBooster = oadm_a.getOutLpStateForExpressLp(stateAtTheInputOfOadmAfterPreamplif, inputFiber, outputFiber)StateForAddedLp(new LpSignalState(lp.getAddTransponderInjectionPower_dBm() , 0.0, 0.0, Double.MAX_VALUE), lp.getAddModuleIndexInOrigin(), lp.getSeqFibers().get(0));
   				 
			 if (fiber.isOriginOadmConfiguredToEqualizeOutput()) 
				startFiberBeforeBooster_powerLp_dBm = linear2dB(numOpticalSlots * fiber.getOriginOadmSpectrumEqualizationTargetBeforeBooster_mwPerGhz().get() * WNetConstants.OPTICALSLOTSIZE_GHZ); 
			 else
				startFiberBeforeBooster_powerLp_dBm = 
				(firstFiber? lp.getAddTransponderInjectionPower_dBm() - oadm_a.getAddModuleAttenuation_dB() : previousFiberInfo.get().getSecond().getPower_dbm() + previousFiber.getDestinationPreAmplifierGain_dB().orElse(0.0))
				- oadm_a.getOadmSwitchFabricAttenuation_dB();
   			 final LpSignalState startFiberAfterBooster_powerLp_dBm;

			 startFiberAfterBooster_powerLp_dBm = startFiberBeforeBooster_powerLp_dBm + fiber.getOriginBoosterAmplifierGain_dB().orElse(0.0); 
			 final double startFiberAfterBooster_cd_psPerNm = firstFiber? fiber.getOriginBoosterAmplifierCdCompensation_psPerNm().orElse(0.0) : previousFiberInfo.get().getSecond().getCd_psPerNm() + previousFiber.getDestinationPreAmplifierCdCompensation_psPerNm().orElse(0.0) + fiber.getOriginBoosterAmplifierCdCompensation_psPerNm().orElse(0.0);
			 final double startFiberAfterBooster_pmd_ps2 = firstFiber? Math.pow(oadm_a.getOadmSwitchFabricPmd_ps(), 2) + Math.pow(fiber.getOriginBoosterAmplifierPmd_ps().orElse(0.0), 2) : 
				 previousFiberInfo.get().getSecond().getPmdSquared_ps2() + Math.pow(previousFiber.getDestinationPreAmplifierPmd_ps().orElse(0.0), 2) + Math.pow(oadm_a.getOadmSwitchFabricPmd_ps(), 2) + Math.pow(fiber.getOriginBoosterAmplifierPmd_ps().orElse(0.0), 2);
			 infoToAdd.getFirst().setPower_dbm(startFiberAfterBooster_powerLp_dBm);
			 infoToAdd.getSecond().setPower_dbm(startFiberAfterBooster_powerLp_dBm + totalJustFiberGain_dB - totalJustFiberAttenuation_dB);
			 infoToAdd.getFirst().setCd_psPerNm(startFiberAfterBooster_cd_psPerNm);
			 infoToAdd.getSecond().setCd_psPerNm(startFiberAfterBooster_cd_psPerNm + totalJustFiberCdBalance_psPerNm);
			 infoToAdd.getFirst().setPmdSquared_ps2(startFiberAfterBooster_pmd_ps2);
			 infoToAdd.getSecond().setPmdSquared_ps2(startFiberAfterBooster_pmd_ps2 + totalJustFiberPmdSquaredBalance_ps2);
//			 put(PERLPINFOMETRICS.POWER_DBM, Pair.of(startFiberAfterBooster_powerLp_dBm, startFiberAfterBooster_powerLp_dBm + totalJustFiberGain_dB - totalJustFiberAttenuation_dB));
//			 infoToAdd.put(PERLPINFOMETRICS.CD_PERPERNM, Pair.of(startFiberAfterBooster_cd_psPerNm , startFiberAfterBooster_cd_psPerNm + totalJustFiberCdBalance_psPerNm));
//			 infoToAdd.put(PERLPINFOMETRICS.PMDSQUARED_PS2, Pair.of(startFiberAfterBooster_pmd_ps2, startFiberAfterBooster_pmd_ps2 + totalJustFiberPmdSquaredBalance_ps2));
			 final double osnrAtStartOfFiber_dB;
			 /* OSNR at the start of fiber */
			 if (firstFiber)
			 {
				 osnrAtStartOfFiber_dB = !fiber.isExistingBoosterAmplifierAtOriginOadm()? Double.MAX_VALUE : linear2dB(osnrContributionEdfaRefBw12dot5GHz_linear(centralFrequency_hz, fiber.getOriginBoosterAmplifierNoiseFactor_dB().get(), startFiberBeforeBooster_powerLp_dBm));
			 }
			 else
			 {
   				 final double powerAtEndOfLastFiber_dBm = previousFiberInfo.get().getSecond().getPower_dbm();
   				 final double osnrAtEndOfLastFiber_dB = previousFiberInfo.get().getSecond ().getOsnrAt12_5GhzRefBw();
   				 final double osnrContributedByOadmPreamplifier_dB = linear2dB(osnrContributionEdfaRefBw12dot5GHz_linear(centralFrequency_hz, previousFiber.getDestinationPreAmplifierNoiseFactor_dB().orElse(-Double.MAX_VALUE), powerAtEndOfLastFiber_dBm));
   				 final double osnrContributedByOadmBooster_dB = linear2dB(osnrContributionEdfaRefBw12dot5GHz_linear(centralFrequency_hz, fiber.getOriginBoosterAmplifierNoiseFactor_dB().orElse(-Double.MAX_VALUE), startFiberBeforeBooster_powerLp_dBm));
   				 osnrAtStartOfFiber_dB = osnrInDbUnitsAccummulation_dB (Arrays.asList(osnrAtEndOfLastFiber_dB , osnrContributedByOadmPreamplifier_dB , osnrContributedByOadmBooster_dB));
			 }
			 final List<Double> osnrAccumulation_db = new ArrayList<> ();
			 osnrAccumulation_db.add(osnrAtStartOfFiber_dB);
			 for (int contOla = 0; contOla < fiber.getOlaGains_dB().size() ; contOla ++)
			 {
				 final double noiseFactor_db = fiber.getOlaNoiseFactor_dB().get(contOla);
				 final double kmFromStartFiber = fiber.getAmplifierPositionsKmFromOrigin_km().get(contOla);
				 final double sumGainsTraversedAmplifiersBeforeMe_db = IntStream.range(0, contOla).mapToDouble(olaIndex -> fiber.getOlaGains_dB().get(olaIndex)).sum();
				 final double lpPowerAtInputOla_dBm = infoToAdd.getFirst().getPower_dbm() - kmFromStartFiber * fiber.getAttenuationCoefficient_dbPerKm() + sumGainsTraversedAmplifiersBeforeMe_db;
				 final double lpCdAtInputOla_perPerNm = infoToAdd.getFirst().getCd_psPerNm() + fiber.getAmplifierPositionsKmFromOrigin_km().get(contOla) * fiber.getChromaticDispersionCoeff_psPerNmKm() + IntStream.range(0, contOla).mapToDouble(ee->fiber.getOlaCdCompensation_psPerNm().get(ee)).sum();
				 final double lpPmdSquaredAtInputOla_perPerNm = infoToAdd.getFirst().getPmdSquared_ps2() + fiber.getAmplifierPositionsKmFromOrigin_km().get(contOla) * Math.pow(fiber.getPmdLinkDesignValueCoeff_psPerSqrtKm(),2) + IntStream.range(0, contOla).mapToDouble(ee->Math.pow(fiber.getOlaPmd_ps().get(ee) , 2)).sum();
				 final double lpOsnrAtInputOla_dB = osnrInDbUnitsAccummulation_dB (osnrAccumulation_db);
				 final double osnrContributionThisOla_db = linear2dB(osnrContributionEdfaRefBw12dot5GHz_linear(centralFrequency_hz, noiseFactor_db, lpPowerAtInputOla_dBm));
				 osnrAccumulation_db.add (osnrContributionThisOla_db); 
				 final double lpOsnrAtOutputOla_dB = osnrInDbUnitsAccummulation_dB (osnrAccumulation_db);
				 final Pair<LpSignalState,LpSignalState> infoThisOla = Pair.of(new LpSignalState(-1, -1, -1, -1), new LpSignalState(-1, -1, -1, -1));
				 infoThisOla.getFirst().setPower_dbm(lpPowerAtInputOla_dBm);
				 infoThisOla.getSecond().setPower_dbm(lpPowerAtInputOla_dBm + fiber.getOlaGains_dB().get(contOla));
				 infoThisOla.getFirst().setCd_psPerNm(lpCdAtInputOla_perPerNm);
				 infoThisOla.getSecond().setCd_psPerNm(lpCdAtInputOla_perPerNm + fiber.getOlaCdCompensation_psPerNm().get(contOla));
				 infoThisOla.getFirst().setPmdSquared_ps2(lpPmdSquaredAtInputOla_perPerNm);
				 infoThisOla.getSecond().setPmdSquared_ps2(lpPmdSquaredAtInputOla_perPerNm + Math.pow(1.0 ,2)); // ERROR?
				 infoThisOla.getFirst().setOsnrAt12_5GhzRefBw(lpOsnrAtInputOla_dB);
				 infoThisOla.getSecond().setOsnrAt12_5GhzRefBw(lpOsnrAtOutputOla_dB); 
//				infoThisOla.put(PERLPINFOMETRICS.POWER_DBM, Pair.of (lpPowerAtInputOla_dBm , lpPowerAtInputOla_dBm + fiber.getOlaGains_dB().get(contOla)));
//				infoThisOla.put(PERLPINFOMETRICS.CD_PERPERNM, Pair.of (lpCdAtInputOla_perPerNm , lpCdAtInputOla_perPerNm + fiber.getOlaCdCompensation_psPerNm().get(contOla)));
//				infoThisOla.put(PERLPINFOMETRICS.PMDSQUARED_PS2, Pair.of (lpPmdSquaredAtInputOla_perPerNm , lpPmdSquaredAtInputOla_perPerNm + Math.pow(1.0 ,2)));
//				infoThisOla.put(PERLPINFOMETRICS.OSNRAT12_5GHZREFBW, Pair.of (lpOsnrAtInputOla_dB , lpOsnrAtOutputOla_dB));
				infoToAddPerOla.put(contOla, infoThisOla);
			 }
			 final double osnrEndOfFiber_dB = osnrInDbUnitsAccummulation_dB (osnrAccumulation_db);
			 infoToAdd.getFirst().setOsnrAt12_5GhzRefBw(Double.MAX_VALUE);
			 infoToAdd.getSecond().setOsnrAt12_5GhzRefBw(osnrEndOfFiber_dB);
   			 previousFiberInfo = Optional.of(infoToAdd);
   		 }
   	 }
   	 
   	 assert perFiberPerLpPerOlaIndexMetric_valStartEnd.keySet().containsAll(wNet.getFibers());
   	 assert wNet.getFibers().stream().allMatch(e->e.getTraversingLps().equals(perFiberPerLpPerOlaIndexMetric_valStartEnd.get(e).keySet()));
   	 assert wNet.getFibers().stream().allMatch(e->e.getTraversingLps().stream().allMatch(lp->perFiberPerLpPerOlaIndexMetric_valStartEnd.get(e).get(lp).size() == e.getNumberOfOpticalLineAmplifiersTraversed()));
   	 
   	 /* Update the per lp information at add and end */
   	 for (WLightpath lp : wNet.getLightpaths())
   	 {
   		 final double centralFrequency_hz = 1e12 * lp.getCentralFrequencyThz();
   		 final LpSignalState vals = new LpSignalState(-1, -1, -1, -1);
   		 final WFiber lastFiber = lp.getSeqFibers().get(lp.getSeqFibers().size()-1);
   		 final WNode lastOadm = lastFiber.getB();
   		 final double inputLastOadm_power_dBm = perFiberPerLpPerMetric_valStartEnd.get(lastFiber).get(lp).getSecond().getPower_dbm();
   		 final double inputLastOadm_cd_psPerNm = perFiberPerLpPerMetric_valStartEnd.get(lastFiber).get(lp).getSecond().getCd_psPerNm();
   		 final double inputLastOadm_pmdSquared_ps2 = perFiberPerLpPerMetric_valStartEnd.get(lastFiber).get(lp).getSecond().getPmdSquared_ps2();
   		 final double inputLastOadm_onsnr_dB = perFiberPerLpPerMetric_valStartEnd.get(lastFiber).get(lp).getSecond().getOsnrAt12_5GhzRefBw();

   		 final double drop_power_dBm = inputLastOadm_power_dBm + lastFiber.getDestinationPreAmplifierGain_dB().orElse(0.0) - lastOadm.getOadmSwitchFabricAttenuation_dB();
   		 final double drop_cd_psPerNm = inputLastOadm_cd_psPerNm + lastFiber.getDestinationPreAmplifierCdCompensation_psPerNm().orElse(0.0);
   		 final double drop_pmdSquared_ps2 = inputLastOadm_pmdSquared_ps2 + Math.pow(lastFiber.getDestinationPreAmplifierPmd_ps().orElse(0.0) ,2) + Math.pow(lastOadm.getOadmSwitchFabricPmd_ps(),2) ;
   		 final double addedOsnrByDropOadm = osnrContributionEdfaRefBw12dot5GHz_linear(centralFrequency_hz, lastFiber.getDestinationPreAmplifierNoiseFactor_dB().orElse(-Double.MAX_VALUE), inputLastOadm_power_dBm);
   		 final double drop_osnr12_5RefBw_dB = osnrInDbUnitsAccummulation_dB(Arrays.asList(inputLastOadm_onsnr_dB , addedOsnrByDropOadm));

   		 vals.setPower_dbm(drop_power_dBm);
   		 vals.setCd_psPerNm(drop_cd_psPerNm);
   		 vals.setPmdSquared_ps2(drop_pmdSquared_ps2);
   		 vals.setOsnrAt12_5GhzRefBw(drop_osnr12_5RefBw_dB);
   		 
   		 perLpPerMetric_valAtDropTransponderEnd.put(lp, vals);
   	 }
   	 
   	 
   	 /* Update the total power per fiber */
   	 for (WFiber fiber : wNet.getFibers())
   	 {
   		 final double powerAtStart_dBm = linear2dB(fiber.getTraversingLps().stream().map(lp->perFiberPerLpPerMetric_valStartEnd.get(fiber).get(lp).getFirst().getPower_dbm()).
   				 mapToDouble (v->dB2linear(v)).sum ());
   		 final double powerAtEnd_dBm = linear2dB(fiber.getTraversingLps().stream().map(lp->perFiberPerLpPerMetric_valStartEnd.get(fiber).get(lp).getSecond().getPower_dbm()).
   				 mapToDouble (v->dB2linear(v)).sum ());
   		 
   		 final List<Double> powerInputOla_dBm = new ArrayList<> ();
   		 final List<Double> powerOutputOla_dBm = new ArrayList<> ();
			 for (int contOla = 0; contOla < fiber.getOlaGains_dB().size() ; contOla ++)
			 {
				 final double kmFromStartFiber = fiber.getAmplifierPositionsKmFromOrigin_km().get(contOla);
				 final double sumGainsTraversedAmplifiersBeforeThisOla_db = IntStream.range(0, contOla).mapToDouble(olaIndex -> fiber.getOlaGains_dB().get(olaIndex)).sum();
				 final double powerAtInputThisOla_dBm = powerAtStart_dBm - kmFromStartFiber * fiber.getAttenuationCoefficient_dbPerKm() + sumGainsTraversedAmplifiersBeforeThisOla_db;
				 final double powerAtOutputThisOla_dBm = powerAtInputThisOla_dBm + fiber.getOlaGains_dB().get(contOla);
				 powerInputOla_dBm.add(powerAtInputThisOla_dBm);
				 powerOutputOla_dBm.add(powerAtOutputThisOla_dBm);
			 }
   		 perFiberTotalPower_valStartEndAndAtEachOlaInputOutput.put(fiber, Quadruple.of(powerAtStart_dBm, powerAtEnd_dBm , powerInputOla_dBm , powerOutputOla_dBm));
   	 }
   	 
   	 return this;
    }
        
    public List<Double> getTotalPowerAtAmplifierInputs_dBm (WFiber fiber)
    {
    	return perFiberTotalPower_valStartEndAndAtEachOlaInputOutput.get(fiber).getThird();
    }
    public List<Double> getTotalPowerAtAmplifierOutputs_dBm (WFiber fiber)
    {
    	return perFiberTotalPower_valStartEndAndAtEachOlaInputOutput.get(fiber).getFourth();
    }
    public double getTotalPowerAtAmplifierInput_dBm (WFiber fiber , int indexAmplifierInFiber)
    {
   	 if (indexAmplifierInFiber < 0 || indexAmplifierInFiber >= fiber.getNumberOfOpticalLineAmplifiersTraversed()) throw new Net2PlanException ("Wrong index");
   	 return perFiberTotalPower_valStartEndAndAtEachOlaInputOutput.get(fiber).getThird().get(indexAmplifierInFiber);
    }
    public double getTotalPowerAtAmplifierOutput_dBm (WFiber fiber , int indexAmplifierInFiber)
    {
   	 if (indexAmplifierInFiber < 0 || indexAmplifierInFiber >= fiber.getNumberOfOpticalLineAmplifiersTraversed()) throw new Net2PlanException ("Wrong index");
   	 return perFiberTotalPower_valStartEndAndAtEachOlaInputOutput.get(fiber).getFourth().get(indexAmplifierInFiber);
    }
    public Pair<LpSignalState,LpSignalState> getOpticalPerformanceOfLightpathAtFiberEnds (WFiber fiber , WLightpath lp)
    {
   	 if (!perFiberPerLpPerMetric_valStartEnd.containsKey(fiber)) return null;
   	 if (!perFiberPerLpPerMetric_valStartEnd.get(fiber).containsKey(lp)) return null;
   	 return perFiberPerLpPerMetric_valStartEnd.get(fiber).get(lp);
    }
    public Pair<Double,Double> getTotalPowerAtFiberEnds_dBm (WFiber fiber)
    {
   	 if (!perFiberTotalPower_valStartEndAndAtEachOlaInputOutput.containsKey(fiber)) return null;
   	 return Pair.of(
   			perFiberTotalPower_valStartEndAndAtEachOlaInputOutput.get(fiber).getFirst (), 
   			perFiberTotalPower_valStartEndAndAtEachOlaInputOutput.get(fiber).getSecond ());
    }
    public LpSignalState getOpticalPerformanceAtTransponderReceiverEnd (WLightpath lp)
    {
    	return perLpPerMetric_valAtDropTransponderEnd.get(lp);
    }
    public Pair<LpSignalState,LpSignalState> getOpticalPerformanceOfLightpathAtAmplifierInputAndOutput (WLightpath lp , WFiber e , int olaIndex)
    {
    	if (olaIndex >= e.getNumberOfOpticalLineAmplifiersTraversed()) throw new Net2PlanException ("Wrong amplifier index");
    	if (!lp.getSeqFibers().contains(e)) throw new Net2PlanException ("Wrong amplifier fiber");
    	final  Pair<LpSignalState,LpSignalState> res = perFiberPerLpPerOlaIndexMetric_valStartEnd.getOrDefault(e , new TreeMap<> ()).getOrDefault(lp, new TreeMap<> ()).get(olaIndex); 
    	if (res == null) throw new Net2PlanException ("Unknown value");
    	return res;
    }
    

    public static double nm2thz (double wavelengthInNm)
    {
    	return constant_c / (1000 * wavelengthInNm);
    }
    public static double thz2nm (double freqInThz)
    {
    	return constant_c / (1000 * freqInThz);
    }
    public static double getLowestFreqfSlotTHz (int slot)
    {
    	return WNetConstants.CENTRALFREQUENCYOFOPTICALSLOTZERO_THZ + (slot - 0.5)  * (WNetConstants.OPTICALSLOTSIZE_GHZ/1000);
    }
    public static double getHighestFreqfSlotTHz (int slot)
    {
    	return WNetConstants.CENTRALFREQUENCYOFOPTICALSLOTZERO_THZ + (slot + 0.5)  * (WNetConstants.OPTICALSLOTSIZE_GHZ/1000);
    }
    
    public boolean isOkOpticalPowerAtAmplifierInputAllOlas (WFiber e)
    {
		final List<Double> minOutputPowerDbm = e.getOlaMinAcceptableOutputPower_dBm();
		final List<Double> maxOutputPowerDbm = e.getOlaMaxAcceptableOutputPower_dBm();
		for (int cont = 0; cont < minOutputPowerDbm.size() ; cont ++)
		{
			final double outputPowerDbm = this.getTotalPowerAtAmplifierOutput_dBm(e, cont);
			if (outputPowerDbm < minOutputPowerDbm.get(cont)) return false;
			if (outputPowerDbm > maxOutputPowerDbm.get(cont)) return false;
		}
		return true;
    }

    private static LpSignalState getStateAfterFiberKm (LpSignalState initialState , WFiber e , double kmOfFiberTraversed)
    {
    	final double power_dbm = initialState.getPower_dbm() - e.getAttenuationCoefficient_dbPerKm() * kmOfFiberTraversed;
    	final double cd_psPerNm = initialState.getCd_psPerNm() + e.getChromaticDispersionCoeff_psPerNmKm() * kmOfFiberTraversed;
    	final double pmdSquared_ps2 = initialState.getPmdSquared_ps2() + Math.pow(e.getPmdLinkDesignValueCoeff_psPerSqrtKm() , 2);
    	return new LpSignalState(power_dbm, cd_psPerNm, pmdSquared_ps2, initialState.getOsnrAt12_5GhzRefBw());
    }
    private static LpSignalState getStateAfterOpticalAmplifier (LpSignalState initialState , double gain_db , double cdCompensation_psPerNm , double pmd_ps , double noiseFigure_dB)
    {
    	final double power_dbm = initialState.getPower_dbm() - gain_db;
    	final double cd_psPerNm = initialState.getCd_psPerNm() + cdCompensation_psPerNm;
    	final double pmdSquared_ps2 = initialState.getPmdSquared_ps2() + Math.pow(pmd_ps , 2);
    	return new LpSignalState(power_dbm, cd_psPerNm, pmdSquared_ps2, initialState.getOsnrAt12_5GhzRefBw());
    }
    
}
