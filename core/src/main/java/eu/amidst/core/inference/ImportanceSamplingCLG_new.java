/*
 *
 *
 *    Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 *    See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 *    The ASF licenses this file to You under the Apache License, Version 2.0 (the "License"); you may not use
 *    this file except in compliance with the License.  You may obtain a copy of the License at
 *
 *            http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software distributed under the License is
 *    distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and limitations under the License.
 *
 *
 */

package eu.amidst.core.inference;

import eu.amidst.core.distribution.GaussianMixture;
import eu.amidst.core.distribution.Multinomial;
import eu.amidst.core.distribution.Normal;
import eu.amidst.core.distribution.UnivariateDistribution;
import eu.amidst.core.exponentialfamily.EF_UnivariateDistribution;
import eu.amidst.core.exponentialfamily.SufficientStatistics;
import eu.amidst.core.models.BayesianNetwork;
import eu.amidst.core.utils.*;
import eu.amidst.core.variables.Assignment;
import eu.amidst.core.variables.HashMapAssignment;
import eu.amidst.core.variables.Variable;
import org.apache.commons.math.distribution.NormalDistributionImpl;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * This class implements the interface {@link InferenceAlgorithm} and defines the Importance Sampling algorithm.
 * J.M. Hammersley and D.C. Handscomb. Monte Carlo Methods. Methuen and Co, London, UK, 1964.
 *
 * <p> For an example of use follow this link
 * <a href="http://amidst.github.io/toolbox/CodeExamples.html#isexample"> http://amidst.github.io/toolbox/CodeExamples.html#isexample </a>  </p>
 */
public class ImportanceSamplingCLG_new extends ImportanceSampling {

    private static final long serialVersionUID = 3697326793626972639L;

    private List<Variable> variablesAPosteriori;
    private List<Variable> variablesAPosteriori_multinomials;
    private List<Variable> variablesAPosteriori_normals;

    private List<SufficientStatistics> SSOfMultinomialVariables;
    private List<List<StreamingUpdateableGaussianMixture>> SSOfGaussianMixtureVariables;
    private List<StreamingUpdateableRobustNormal> SSOfNormalVariables;


    private double logProbOfEvidence;

    private boolean parallelMode = true;
    private boolean useGaussianMixtures = false;

    private double mixtureOfGaussiansInitialVariance = 50;
    private double mixtureOfGaussiansNoveltyRate = 0.0001;

    private class StreamingUpdateableRobustNormal {

        Variable variable;
//
//        private double log_sum_weights = 0;
//        private double log_sum_positive_terms = 0;
//        private double log_sum_negative_terms = 0;
//        private double log_sum_positive_terms_sq = 0;
//        private double log_sum_negative_terms_sq = 0;

        private double log_sum_weights = Double.NEGATIVE_INFINITY;
        private double log_sum_positive_terms = Double.NEGATIVE_INFINITY;
        private double log_sum_negative_terms = Double.NEGATIVE_INFINITY;
        private double log_sum_positive_terms_sq = Double.NEGATIVE_INFINITY;
        private double log_sum_negative_terms_sq = Double.NEGATIVE_INFINITY;


        NormalDistributionImpl normalDistribution;

        public StreamingUpdateableRobustNormal(Variable var) {
            this.variable=var;
        }

        public void update(double dataPoint, double logWeight) {

            double logThisTerm, logThisTerm_sq;
            boolean output=false;

            if(output) {
                System.out.println("NUEVO DATO: " + dataPoint + ", CON PESO: " + logWeight);
            }

            if(dataPoint>0) {
                logThisTerm = logWeight + Math.log(dataPoint);
                log_sum_positive_terms = RobustOperations.robustSumOfLogarithms(log_sum_positive_terms, logThisTerm);

                logThisTerm_sq = logWeight + 2 * Math.log(dataPoint);
                log_sum_positive_terms_sq = RobustOperations.robustSumOfLogarithms(log_sum_positive_terms_sq, logThisTerm_sq);
            }
            else {
                logThisTerm = logWeight + Math.log(-dataPoint);
                log_sum_negative_terms = RobustOperations.robustSumOfLogarithms(log_sum_negative_terms, logThisTerm);

                logThisTerm_sq = logWeight + 2 * Math.log(-dataPoint);
                log_sum_negative_terms_sq = RobustOperations.robustSumOfLogarithms(log_sum_negative_terms_sq, logThisTerm_sq);
            }

            log_sum_weights = (log_sum_weights == Double.NEGATIVE_INFINITY ? logWeight : RobustOperations.robustSumOfLogarithms(log_sum_weights, logWeight));

            if(output) {
                System.out.println("log_sum_p: " + log_sum_positive_terms + ", log_sum_n:" + log_sum_negative_terms);
                System.out.println("log_sum_p_sq: " + log_sum_positive_terms_sq + ", log_sum_n_sq:" + log_sum_negative_terms_sq);
                System.out.println("log_sum_w: " + log_sum_weights);

            }
        }

        public Normal getNormal() {
            double logSumTerms, logSumTerms_sq;
            double meanEstimate, varEstimate;

            if(log_sum_positive_terms > log_sum_negative_terms) {
                logSumTerms = RobustOperations.robustDifferenceOfLogarithms(log_sum_positive_terms, log_sum_negative_terms);
                meanEstimate = Math.exp( logSumTerms - log_sum_weights );
            } else {
                logSumTerms = RobustOperations.robustDifferenceOfLogarithms(log_sum_positive_terms,log_sum_negative_terms);
                meanEstimate = - Math.exp( logSumTerms - log_sum_weights );
            }

            logSumTerms_sq = RobustOperations.robustSumOfLogarithms(log_sum_positive_terms_sq,log_sum_negative_terms_sq);
            varEstimate = Math.exp(logSumTerms_sq - log_sum_weights);

            varEstimate = varEstimate - Math.pow(meanEstimate,2);

            Normal normalDistribution = new Normal(variable);
            normalDistribution.setMean(meanEstimate);
            normalDistribution.setVariance(varEstimate);

            return normalDistribution;
        }
    }

    private class StreamingUpdateableGaussianMixture {

        private double initial_variance = 50;
        private double tau_novelty = 0.0001;

        private int M = 0;
        private List<Double> mean;
        private List<Double> variance;
        private List<Double> componentWeight;
        private List<Double> log_sumProbs;

        NormalDistributionImpl normalDistribution;


        public void setInitialVariance(double initial_variance) {
            this.initial_variance = initial_variance;
        }

        public void setNoveltyRate(double tau_novelty) {
            this.tau_novelty = tau_novelty;
        }

        public void update(double dataPoint, double logWeight) {

            boolean output=false;

            if(output) {
                System.out.println("NUEVO DATO: " + dataPoint + ", CON PESO: " + logWeight);
            }
            // CREATE FIRST COMPONENT
            if (M==0) {
                M=1;

                mean = new ArrayList<>();

                variance = new ArrayList<>();
                componentWeight = new ArrayList<>();
                log_sumProbs = new ArrayList<>();

                mean.add(dataPoint);
                variance.add(initial_variance);
                log_sumProbs.add(logWeight);
                componentWeight.add(1.0);
            }
            // UPDATE EXISTING COMPONENTS
            else {

                double [] condition = new double[M];
                double [] probs_xj = new double[M];
                double [] probs_jx = new double[M];

                for (int i = 0; i < M; i++) {
                    normalDistribution = new NormalDistributionImpl( mean.get(i), Math.sqrt(variance.get(i)) );
                    double p_xj = normalDistribution.density(dataPoint);
                    probs_xj[i] = p_xj;
                    condition[i] = ( p_xj >= tau_novelty / Math.sqrt(2*Math.PI*variance.get(i)) ) ? 1 : 0;
                    probs_jx[i] = probs_xj[i] * componentWeight.get(i);
                }

                double sumProbsWeights = Arrays.stream(probs_jx).sum();

                for (int i = 0; i < M; i++) {
                    probs_jx[i] = probs_jx[i] / sumProbsWeights;
                }

                // IF DATAPOINT DOES NOT MATCH ANY EXISTING COMPONENT, CREATE A NEW ONE
                if (Arrays.stream(condition).sum()==0) {
                    M=M+1;

                    mean.add(dataPoint);
                    variance.add(initial_variance);
                    log_sumProbs.add(logWeight);
                    componentWeight.add(1.0);

                }
                // ELSE, UPDATE PARAMETERS OF THE COMPONENTS
                else {

                    //TODO: review operations and check robustness
                    for (int i = 0; i < M; i++) {

                        double p_jx = probs_jx[i];
                        double oldMean = mean.get(i);


                        double log_p_jx_weighted = Math.log(p_jx) + logWeight;    // p_jx_weighted = p_jx * exp(logWeight)
                        log_sumProbs.set(i, RobustOperations.robustSumOfLogarithms(log_sumProbs.get(i), log_p_jx_weighted));

                        double w = Math.exp(log_p_jx_weighted - log_sumProbs.get(i));

                        if(w==0) {
                            continue;
                        }

                        double diff_oldMean = dataPoint - oldMean;
                        double aux = w * diff_oldMean;
                        double newMean = oldMean + aux;


                        double diff_newMean = dataPoint - newMean;
//                        double aux2_new = Math.log(w) + 2 * diff_newMean;
//                        double aux2_old = Math.log(w) + 2 * diff_oldMean;


                        if(output==true) {
                            System.out.println("p_jx:" + p_jx);
                            System.out.println("w: " + w);
                            System.out.println("oldMean: " + oldMean);
                            System.out.println("diff_oldMean: " + diff_oldMean);
                            System.out.println("newMean: " + newMean);
                            System.out.println("diff_newMean: " + diff_newMean);
                        }


                        double oldVar = variance.get(i);

//                        double aux2_plus = w * ( Math.pow(diff_newMean,2) - oldVar);
//                        double aux2_minus = Math.pow(newMean-oldMean,2);
//
//                        double newVar = variance.get(i) + aux2_plus;


                        double aux2_plus = w * Math.pow(diff_newMean,2);
                        double aux2_minus = Math.pow(w,2) * Math.pow(diff_oldMean,2);

                        double newVar = (1-w) * oldVar + aux2_plus;

                        if(newVar > aux2_minus) {
                            newVar = newVar - aux2_minus;
                        }
                        else {
                            newVar = 0.01;
                        }

                        mean.set(i,newMean);
                        variance.set(i,newVar);

                    }
//
//                    //double sum_sumProbs = log_sumProbs.stream().mapToDouble(Double::doubleValue).sum();
//                    double logSum_log_sumProbs = log_sumProbs.stream().reduce(RobustOperations::robustSumOfLogarithms).get();
//
////                    if(logSum_log_sumProbs!=0.0) {
//                        for (int i = 0; i < M; i++) {
//                            componentWeight.set(i, Math.exp(log_sumProbs.get(i) - logSum_log_sumProbs));
//                        }
////                    }
////                    else {
////                        for (int i = 0; i < M; i++) {
////                            componentWeight.set(i, 1.0/M);
////                        }
////                    }
//
//                    if(output==true) {
//                        System.out.println(Arrays.toString(log_sumProbs.toArray()));
//                        System.out.println(Arrays.toString(componentWeight.toArray()));
//                    }



//
//                    for (int i = 0; i < M; i++) {
//                        componentWeight.set(i, Math.exp(log_sumProbs.get(i) - logSum_log_sumProbs));
//                    }
                }


                double logSum_log_sumProbs = log_sumProbs.stream().reduce(RobustOperations::robustSumOfLogarithms).get();

                List<Integer> significantComponents = new ArrayList<>();

                double threshold = 1E-3;
                for (int i = 0; i < M; i++) {
                    double newWeight = Math.exp(log_sumProbs.get(i) - logSum_log_sumProbs);
                    componentWeight.set(i, newWeight);

                    if(newWeight > threshold) {
                        significantComponents.add(i);
                    }
                }

                if(output==true) {
                    System.out.println("M: " + M + " with " + componentWeight.stream().filter(a -> a < threshold).count() + " negligible");
                }
                int newM = significantComponents.size();

                List<Double> newList_logMean = new ArrayList<>();
                List<Double> newList_logVariance = new ArrayList<>();
                List<Double> newList_componentWeight = new ArrayList<>();
                List<Double> newList_log_sumProbs = new ArrayList<>();

                for (int i = 0; i < newM; i++) {
                    newList_logMean.add(mean.get(significantComponents.get(i)));
                    newList_logVariance.add(variance.get(significantComponents.get(i)));
                    newList_componentWeight.add(componentWeight.get(significantComponents.get(i)));
                    newList_log_sumProbs.add(log_sumProbs.get(significantComponents.get(i)));
                }

                newList_componentWeight.sort(Double::compareTo);

                M = newM;

                mean = newList_logMean;
                variance = newList_logVariance;
                componentWeight = newList_componentWeight;
                log_sumProbs = newList_log_sumProbs;

                logSum_log_sumProbs = log_sumProbs.stream().reduce(RobustOperations::robustSumOfLogarithms).get();

                for (int i = 0; i < M; i++) {
                    double newWeight = Math.exp(log_sumProbs.get(i) - logSum_log_sumProbs);
                    componentWeight.set(i, newWeight);
                }


                if(output==true) {
                    System.out.println(Arrays.toString(log_sumProbs.toArray()));
                    System.out.println(Arrays.toString(componentWeight.toArray()));
                }
            }

            if(output) {
                System.out.println("COMPONENTES:");
                for (int i = 0; i < M; i++) {
                    System.out.println("Comp. " + i + "; media: " + mean.get(i) + "; var: " + variance.get(i) + "; peso: " + componentWeight.get(i));
                }
                System.out.println();
            }

        }

        public GaussianMixture getGaussianMixture() {

            double [] parameters = new double[3*M];

            for (int i = 0; i < M; i++) {
                parameters[3 * i + 0] = componentWeight.get(i);
                parameters[3 * i + 1] = mean.get(i);
                parameters[3 * i + 2] = variance.get(i);
            }
            GaussianMixture result = new GaussianMixture(parameters);

            return result;
        }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void setModel(BayesianNetwork model_) {
        super.setModel(model_);

//        this.model = Serialization.deepCopy(model_);



        //setSamplingModel(model_);
//        this.samplingModel = model;
//        this.causalOrder = Utils.getTopologicalOrder(model.getDAG());
//        this.sameSamplingModel=true;

//        evidence=null;
//        weightedSampleStream=null;
//        this.weightedSampleList=null;

        if(this.model.getVariables().getListOfVariables().stream().anyMatch(variable -> !(variable.isMultinomial() || variable.isNormal()))) {
            throw new UnsupportedOperationException("All the Variable objects must be Multinomial or Normal in a CLG network");
        }

        variablesAPosteriori = model.getVariables().getListOfVariables();
        SSOfMultinomialVariables = new ArrayList<>(variablesAPosteriori.size());

        variablesAPosteriori.stream().forEachOrdered(variable -> {

            EF_UnivariateDistribution ef_univariateDistribution = variable.newUnivariateDistribution().toEFUnivariateDistribution();
            ArrayVector arrayVector = new ArrayVector(ef_univariateDistribution.sizeOfSufficientStatistics());
            SSOfMultinomialVariables.add(arrayVector);
        });

        this.variablesAPosteriori_multinomials = variablesAPosteriori.stream().filter(Variable::isMultinomial).collect(Collectors.toList());
        this.variablesAPosteriori_normals = variablesAPosteriori.stream().filter(Variable::isNormal).collect(Collectors.toList());

        this.initializeSufficientStatistics();

    }


//    public Stream<Assignment> getSamples() {
//
//        LocalRandomGenerator randomGenerator = new LocalRandomGenerator(seed);
//
//        IntStream weightedSampleStream = IntStream.range(0, sampleSize).parallel();
//
//        if (!parallelMode) {
//            weightedSampleStream = weightedSampleStream.sequential();
//        }
//
//        return weightedSampleStream.mapToObj(i -> {
//            WeightedAssignment weightedSample = generateSample(randomGenerator.current());
//            return weightedSample.getAssignment();
//        });
//    }

    public void setGaussianMixturePosteriors(boolean useGaussianMixtures) {
        this.useGaussianMixtures = useGaussianMixtures;

        this.initializeSufficientStatistics();
    }

//    public List<SufficientStatistics> getSSMultinomialVariablesAPosteriori() {
//        return SSOfMultinomialVariables;
//    }



    public void setVariablesOfInterest(List<Variable> variablesAPosterior) throws InvalidObjectException {

        if (variablesAPosterior.stream().anyMatch(variable -> !model.getVariables().getListOfVariables().contains(variable))) {
            throw new InvalidObjectException("All variables in the list must be included in the Bayesian network");
        }
        this.variablesAPosteriori = variablesAPosterior;
        this.variablesAPosteriori_multinomials = variablesAPosteriori.stream().filter(Variable::isMultinomial).collect(Collectors.toList());
        this.variablesAPosteriori_normals = variablesAPosteriori.stream().filter(Variable::isNormal).collect(Collectors.toList());

        this.initializeSufficientStatistics();

    }


    public void setMixtureOfGaussiansInitialVariance(double mixtureOfGaussiansInitialVariance) {
        this.mixtureOfGaussiansInitialVariance = mixtureOfGaussiansInitialVariance;
        if(useGaussianMixtures) {
            for (int i = 0; i < SSOfGaussianMixtureVariables.size(); i++) {
                this.SSOfGaussianMixtureVariables.get(i).forEach(object -> object.setInitialVariance(this.mixtureOfGaussiansInitialVariance));
            }

        }
    }

    public void setMixtureOfGaussiansNoveltyRate(double mixtureOfGaussiansNoveltyRate) {
        this.mixtureOfGaussiansNoveltyRate = mixtureOfGaussiansNoveltyRate;
        if(useGaussianMixtures) {
            for (int i = 0; i < SSOfGaussianMixtureVariables.size(); i++) {
                this.SSOfGaussianMixtureVariables.get(i).forEach(object -> object.setNoveltyRate(this.mixtureOfGaussiansNoveltyRate));
            }
        }
    }

    public void setParallelMode(boolean parallelMode_) {
        this.parallelMode = parallelMode_;
        this.initializeSufficientStatistics();
    }

    private void initializeSufficientStatistics() {
        // INITIALIZE SUFFICIENT STATISTICS FOR MULTINOMIAL VARIABLES
        this.SSOfMultinomialVariables = new ArrayList<>();

        this.variablesAPosteriori.stream().filter(Variable::isMultinomial).forEachOrdered(variable -> {

            EF_UnivariateDistribution ef_univariateDistribution = variable.newUnivariateDistribution().toEFUnivariateDistribution();
            ArrayVector arrayVector = new ArrayVector(ef_univariateDistribution.sizeOfSufficientStatistics());

            this.SSOfMultinomialVariables.add(arrayVector);
        });

        int parallelism=1;
        if(parallelMode) {
            parallelism = Runtime.getRuntime().availableProcessors();
        }

        // INITIALIZE "SUFFICIENT STATISTICS" FOR NORMAL VARIABLES, TO BUILD A NORMAL OR A GAUSSIAN MIXTURE
        if(useGaussianMixtures) {
            this.SSOfGaussianMixtureVariables = new ArrayList<>();

            for (int i = 0; i < parallelism; i++) {
                ArrayList arrayList = new ArrayList();

                this.variablesAPosteriori.stream().filter(Variable::isNormal).forEachOrdered(variable -> {
                    StreamingUpdateableGaussianMixture streamingPosteriorDistribution = new StreamingUpdateableGaussianMixture();
                    streamingPosteriorDistribution.setInitialVariance(this.mixtureOfGaussiansInitialVariance);
                    streamingPosteriorDistribution.setNoveltyRate(this.mixtureOfGaussiansNoveltyRate);
                    arrayList.add(streamingPosteriorDistribution);
                });

                this.SSOfGaussianMixtureVariables.add(arrayList);
            }


        }
        else {
            this.SSOfNormalVariables = new ArrayList<>();
            this.variablesAPosteriori.stream().filter(Variable::isNormal).forEachOrdered(variable -> {
                StreamingUpdateableRobustNormal streamingPosteriorDistribution = new StreamingUpdateableRobustNormal(variable);
                this.SSOfNormalVariables.add(streamingPosteriorDistribution);
            });
        }
    }

    //
//    /**
//     * Returns a {@link Stream} containing the drawn samples after running the inference.
//     * @return a {@link Stream} of {@link HashMapAssignment} objects.
//     */
//    public Stream<Assignment> getSamples() {
//
//        if(keepDataOnMemory) {
//            weightedSampleStream = weightedSampleList.stream().sequential();
//        }
//        if(parallelMode) {
//            weightedSampleStream.parallel();
//        }
//        return weightedSampleStream.map(wsl -> wsl.assignment);
//    }

    private void updatePosteriorDistributions(Assignment sample, double logWeight, int parallelProcessIndex) {

        variablesAPosteriori_multinomials.stream().forEach(variable -> {

            int j = variablesAPosteriori_multinomials.indexOf(variable);

            EF_UnivariateDistribution ef_univariateDistribution = variable.newUnivariateDistribution().toEFUnivariateDistribution();

            ArrayVector SSposterior = new ArrayVector(ef_univariateDistribution.sizeOfSufficientStatistics());
            SSposterior.copy(SSOfMultinomialVariables.get(j));

            ArrayVector newSSposterior;

            ArrayVector SSsample = new ArrayVector(ef_univariateDistribution.sizeOfSufficientStatistics());
            SSsample.copy(ef_univariateDistribution.getSufficientStatistics(sample));

            if(evidence!=null) {
                SSsample.multiplyBy(logWeight);
            }


            newSSposterior = RobustOperations.robustSumOfMultinomialLogSufficientStatistics(SSposterior, SSsample);

            SSOfMultinomialVariables.set(j,newSSposterior);
        });



        variablesAPosteriori_normals.stream().forEach(variable -> {

            int j = variablesAPosteriori_normals.indexOf(variable);


            double dataPoint = sample.getValue(variable);

            if(useGaussianMixtures) {
                SSOfGaussianMixtureVariables.get(parallelProcessIndex).get(j).update(dataPoint, logWeight);
            }
            else {
                SSOfNormalVariables.get(j).update(dataPoint, logWeight);
            }

        });


//        int nVarsAPosteriori = variablesAPosteriori.size();
//
//        IntStream.range(0,nVarsAPosteriori).forEach(i -> {
//
//            Variable variable = variablesAPosteriori.get(i);
//
//
//            if(variable.isMultinomial()) {
//
//                EF_UnivariateDistribution ef_univariateDistribution = variable.newUnivariateDistribution().toEFUnivariateDistribution();
//
////            SufficientStatistics SSposterior = SSOfMultinomialVariables.get(i);
////            SufficientStatistics SSsample = ef_univariateDistribution.getSufficientStatistics(sample);
//
//                ArrayVector SSposterior = new ArrayVector(ef_univariateDistribution.sizeOfSufficientStatistics());
//                SSposterior.copy(SSOfMultinomialVariables.get(i));
//
//                ArrayVector newSSposterior;
//
//                ArrayVector SSsample = new ArrayVector(ef_univariateDistribution.sizeOfSufficientStatistics());
//                SSsample.copy(ef_univariateDistribution.getSufficientStatistics(sample));
//
//                if(evidence!=null) {
//                    SSsample.multiplyBy(logWeight);
//                }
//
////                ArrayVector SS = new ArrayVector(ef_univariateDistribution.getMomentParameters().size());
////                SS.copy(ef_univariateDistribution.getSufficientStatistics(sample));
//
////                System.out.println(Arrays.toString(SSposterior.toArray()));
////                System.out.println(Arrays.toString(SSsample.toArray()));
////                System.out.println(Arrays.toString(SS.toArray()));
////                System.out.println();
//
//                newSSposterior = RobustOperations.robustSumOfMultinomialLogSufficientStatistics(SSposterior, SSsample);
//
//                SSOfMultinomialVariables.set(i,newSSposterior);
//            }
//            else if(variable.isNormal()) {
//
//                double dataPoint = sample.getValue(variable);
//                SSOfGaussianMixtureVariables.get(i).update(dataPoint, logWeight);
//
//            }
//            else {
//                throw new UnsupportedOperationException("ImportanceSamplingCLG.updatePosteriorDistributions() variable distribution not supported");
//            }

//        });
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public double getExpectedValue(Variable var, Function<Double,Double> function) {

//        List<Double> sum = weightedSampleStream
//                .map(ws -> Arrays.asList(Math.exp(ws.logWeight), Math.exp(ws.logWeight) * function.apply(ws.assignment.getValue(var))))
//                .filter(array -> (Double.isFinite(array.get(0)) && Double.isFinite(array.get(1)) ))
//                .reduce(Arrays.asList(new Double(0.0), new Double(0.0)), (e1, e2) -> Arrays.asList(e1.get(0) + e2.get(0), e1.get(1) + e2.get(1)));
//
//        return sum.get(1)/sum.get(0);


        LocalRandomGenerator randomGenerator = new LocalRandomGenerator(seed);
        IntStream weightedSampleStream = IntStream.range(0, sampleSize).parallel();

        if (!parallelMode) {
            weightedSampleStream = weightedSampleStream.sequential();
        }

        double[] logSumWeights = weightedSampleStream
                .mapToObj(i -> {
                    WeightedAssignment weightedSample = generateSample(randomGenerator.current());

                    return new double[]{Math.exp(weightedSample.getWeight()), Math.exp(weightedSample.getWeight()) * function.apply(weightedSample.getAssignment().getValue(var))};
                })
                .filter(doubleArray -> Double.isFinite(doubleArray[0]) && Double.isFinite(doubleArray[1]))
                .reduce(((doubleArray1, doubleArray2) -> {
                    double [] result = new double[2];
//                    result[0] = robustSumOfLogarithms(doubleArray1[0],doubleArray2[0]);
//                    result[1] = robustSumOfLogarithms(doubleArray1[1],doubleArray2[1]);
                    result[0] = (doubleArray1[0] + doubleArray2[0]);
                    result[1] = (doubleArray1[1] + doubleArray2[1]);
                    return result;
                })).get();

        System.out.println(logSumWeights[1]);
        System.out.println(logSumWeights[0]);
        return logSumWeights[1]/logSumWeights[0];
    }

    /**
    * {@inheritDoc}
    */
    @Override
    //TODO For continuous variables, instead of returning a Gaussian distributions, we should return a Mixture of Gaussians!!
    public <E extends UnivariateDistribution> E getPosterior(Variable variable) {

        if(variable.isMultinomial()) {

            EF_UnivariateDistribution ef_univariateDistribution = variable.newUnivariateDistribution().toEFUnivariateDistribution();

            ArrayVector sumSS = new ArrayVector(ef_univariateDistribution.sizeOfSufficientStatistics());
            sumSS.copy(SSOfMultinomialVariables.get(variablesAPosteriori_multinomials.indexOf(variable)));
            //System.out.println(Arrays.toString(sumSS.toArray()));

            sumSS = RobustOperations.robustNormalizationOfLogProbabilitiesVector(sumSS);

            ef_univariateDistribution.setMomentParameters((SufficientStatistics)sumSS);
            Multinomial posteriorDistribution = ef_univariateDistribution.toUnivariateDistribution();
            posteriorDistribution.setProbabilities(Utils.normalize(posteriorDistribution.getParameters()));

            return (E)posteriorDistribution;
        }


//         TODO Could we build this object in a general way for Multinomial and Normal?
        else {
            //(variable.isNormal())

//            throw new UnsupportedOperationException("ImportanceSamplingCLG.getPosterior() not supported yet for non-multinomial distributions");
            int j = variablesAPosteriori_normals.indexOf(variable);

            if(useGaussianMixtures) {
                return (E) SSOfGaussianMixtureVariables.get(0).get(j).getGaussianMixture();
            }
            else {
                return (E) SSOfNormalVariables.get(j).getNormal();
            }
        }


    }

//    /**
//     * {@inheritDoc}
//     */
//    @Override
//    //TODO For continuous variables, instead of returning a Gaussian distributions, we should return a Mixture of Gaussians!!
//    public <E extends UnivariateDistribution> E getPosterior(Variable var) {
//
//        Variable samplingVar = this.samplingModel.getVariables().getVariableByName(var.getName());
//        EF_UnivariateDistribution ef_univariateDistribution = samplingVar.newUnivariateDistribution().toEFUnivariateDistribution();
//
//        // TODO Could we build this object in a general way for Multinomial and Normal?
//        if (!samplingVar.isMultinomial()) {
//            throw new UnsupportedOperationException("ImportanceSamplingCLG.getPosterior() not supported yet for non-multinomial distributions");
//
//        }
//
//        if(keepDataOnMemory) {
//            weightedSampleStream = weightedSampleList.stream().sequential();
//        } else {
//            computeWeightedSampleStream(false);
//        }
//
//        if(parallelMode) {
//            weightedSampleStream.parallel();
//        }
//
//        if (!keepDataOnMemory) {
//            weightedSampleList = weightedSampleStream.collect(Collectors.toList());
//        }
//
////        long samples0 = weightedSampleList.stream().mapToInt(weightedAssignment -> (int)weightedAssignment.assignment.getValue(var)).filter(i -> i==0).count();
////        long samples1 = weightedSampleList.stream().mapToInt(weightedAssignment -> (int)weightedAssignment.assignment.getValue(var)).filter(i -> i==1).count();
//
////        System.out.println("Values 0:" +  samples0);
////        System.out.println("Values 1:" +  samples1);
////
////        System.out.println("Rate 0:" +  ((double)samples0)/(samples0+samples1));
////        System.out.println("Rate 1:" +  ((double)samples1)/(samples0+samples1));
//        ArrayVector sumSS = weightedSampleStream
//                .map(e -> {
//                    ArrayVector SS = new ArrayVector(ef_univariateDistribution.getMomentParameters().size());
//                    SS.copy(ef_univariateDistribution.getSufficientStatistics(e.assignment));
//                    SS.multiplyBy(e.logWeight);  //Remember: logWeight is actually log-logWeight
//
////                    if(e.logWeight>=0) {
////                        System.out.println(Arrays.toString(((ArrayVector) ef_univariateDistribution.getSufficientStatistics(e.assignment)).toArray()));
////                        System.out.println(e.logWeight);
////                    }
//                    return SS;
//                })
//                .reduce(this::robustSumOfMultinomialLogSufficientStatistics).get();
//
//
//        sumSS = robustNormalizationOfLogProbabilitiesVector(sumSS);
//
//        ef_univariateDistribution.setMomentParameters((SufficientStatistics)sumSS);
//
//        Distribution posteriorDistribution = ef_univariateDistribution.toUnivariateDistribution();
//        //((Multinomial) posteriorDistribution).setProbabilities(Utils.normalize(sumSS.toArray()));
//
//        return (E)posteriorDistribution;
//    }


    private void runInferenceForGaussianMixtures() {

        int parallelism = Runtime.getRuntime().availableProcessors();

        IntStream parallelProcesses = IntStream.range(0, parallelism).parallel();

        if (!parallelMode) {
            parallelProcesses = parallelProcesses.sequential();
        }

        double logSumWeights = parallelProcesses.mapToDouble(parallelProcessIndex -> {
            IntStream weightedSampleStream = IntStream.range(0, sampleSize/parallelism).sequential();

            return weightedSampleStream
                   .mapToDouble(i -> {
                       LocalRandomGenerator randomGenerator = new LocalRandomGenerator(seed + i);
                       WeightedAssignment weightedSample = generateSample(randomGenerator.current());
                       //System.out.println(weightedSample.assignment.outputString());
                       //System.out.println(weightedSample.logWeight);
                       updatePosteriorDistributions(weightedSample.getAssignment(),weightedSample.getWeight(),parallelProcessIndex);
                       //updateLogProbabilityOfEvidence(weightedSample.logWeight);
                       //logProbOfEvidence = robustSumOfLogarithms(logProbOfEvidence,weightedSample.logWeight-Math.log(sampleSize));
                       //System.out.println(weightedSample.logWeight);
                       //System.out.println(logProbOfEvidence);

                       return weightedSample.getWeight();

                   }).reduce(RobustOperations::robustSumOfLogarithms).getAsDouble();

        }).reduce(RobustOperations::robustSumOfLogarithms).getAsDouble();

        if (evidence!=null) {
            logProbOfEvidence = logSumWeights - Math.log(sampleSize);
        }
        else {
            logProbOfEvidence = 0;
        }






        //        return weightedSampleStream.mapToDouble(ws -> ws.logWeight - Math.log(sampleSize)).reduce(this::robustSumOfLogarithms).getAsDouble();


        //logProbOfEvidence = robustSumOfLogarithms(logProbOfEvidence,-Math.log(sampleSize));



//        if(saveDataOnMemory_) {
//            weightedSampleList = weightedSampleStream.collect(Collectors.toList());
//            //weightedSampleList.forEach(weightedAssignment -> System.out.println("Weight: " + weightedAssignment.logWeight + " for assignment " + weightedAssignment.assignment.getValue(this.model.getVariables().getListOfVariables().get(0)) + " prob " + model.getLogProbabiltyOf(weightedAssignment.assignment)));
//        }


    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void runInference() {

        if(useGaussianMixtures) {
            this.runInferenceForGaussianMixtures();
            return;
        }

        LocalRandomGenerator randomGenerator = new LocalRandomGenerator(seed);

        IntStream weightedSampleStream = IntStream.range(0, sampleSize).parallel();

        if (!parallelMode) {
            weightedSampleStream = weightedSampleStream.sequential();
        }

//            weightedSampleStream = IntStream.range(0, sampleSize).parallel()
//                    .mapToObj(i -> generateSample(randomGenerator.current()));
//        } else {
//            weightedSampleStream = IntStream.range(0, sampleSize).sequential()
//                    .mapToObj(i -> generateSample(randomGenerator.current()));
//        }

        double logSumWeights = weightedSampleStream
                .mapToDouble(i -> {
                    WeightedAssignment weightedSample = generateSample(randomGenerator.current());
                    //System.out.println(weightedSample.assignment.outputString());
                    //System.out.println(weightedSample.logWeight);
                    updatePosteriorDistributions(weightedSample.getAssignment(),weightedSample.getWeight(),0);
                    //updateLogProbabilityOfEvidence(weightedSample.logWeight);


                    //logProbOfEvidence = robustSumOfLogarithms(logProbOfEvidence,weightedSample.logWeight-Math.log(sampleSize));

                    //System.out.println(weightedSample.logWeight);

                    //System.out.println(logProbOfEvidence);

                    return weightedSample.getWeight();

                }).reduce(RobustOperations::robustSumOfLogarithms).getAsDouble();

        //        return weightedSampleStream.mapToDouble(ws -> ws.logWeight - Math.log(sampleSize)).reduce(this::robustSumOfLogarithms).getAsDouble();


        //logProbOfEvidence = robustSumOfLogarithms(logProbOfEvidence,-Math.log(sampleSize));

        if (evidence!=null) {
            this.logProbOfEvidence = logSumWeights - Math.log(sampleSize);
        }
        else {
            this.logProbOfEvidence = 0;
        }

//        if(saveDataOnMemory_) {
//            weightedSampleList = weightedSampleStream.collect(Collectors.toList());
//            //weightedSampleList.forEach(weightedAssignment -> System.out.println("Weight: " + weightedAssignment.logWeight + " for assignment " + weightedAssignment.assignment.getValue(this.model.getVariables().getListOfVariables().get(0)) + " prob " + model.getLogProbabiltyOf(weightedAssignment.assignment)));
//        }


    }


    public static void main(String[] args) throws IOException, ClassNotFoundException {

        // this.oldMain();

        int nMultinomialVars = 50;
        int nGaussianVars = 50;

        BayesianNetworkGenerator.setNumberOfMultinomialVars(nMultinomialVars,2);
        BayesianNetworkGenerator.setNumberOfGaussianVars(nGaussianVars);
        BayesianNetworkGenerator.setNumberOfLinks((int)1.5*(nMultinomialVars+nGaussianVars));
        BayesianNetworkGenerator.setSeed(9463);
        BayesianNetwork bn = BayesianNetworkGenerator.generateBayesianNetwork();
        System.out.println(bn);


        ImportanceSamplingCLG_new importanceSamplingCLG = new ImportanceSamplingCLG_new();
        importanceSamplingCLG.setModel(bn);
        //importanceSamplingCLG.setSamplingModel(vmp.getSamplingModel());


        Assignment evidence = new HashMapAssignment();
        for (int i = 1; i < nGaussianVars; i++) {
            evidence.setValue(bn.getVariables().getVariableByName("GaussianVar" + Integer.toString(i)),0);
        }
        importanceSamplingCLG.setEvidence(evidence);


        importanceSamplingCLG.setParallelMode(true);
        importanceSamplingCLG.setSampleSize(100000);
        importanceSamplingCLG.setSeed(23623);

        Variable gaussianVar0 = bn.getVariables().getVariableByName("GaussianVar0");

        List<Variable> varsPosteriori = new ArrayList<>();
        varsPosteriori.add(gaussianVar0);
        importanceSamplingCLG.setVariablesOfInterest(varsPosteriori);

        importanceSamplingCLG.runInference();


        System.out.println(gaussianVar0.getName() + " conditional distribution: \n" + bn.getConditionalDistribution(gaussianVar0).toString());
        System.out.println(gaussianVar0.getName() + " posterior distribution: \n" + importanceSamplingCLG.getPosterior(gaussianVar0).toString());
        //System.out.println(importanceSamplingCLG.getPosterior(bn.getVariables().getVariableByName("GaussianVar1")).toString());
        //System.out.println(importanceSamplingCLG.getPosterior(bn.getVariables().getVariableByName("GaussianVar2")).toString());

    }

}