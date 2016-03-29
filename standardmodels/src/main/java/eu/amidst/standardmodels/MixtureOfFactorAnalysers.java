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

package eu.amidst.standardmodels;

import eu.amidst.core.datastream.Attributes;
import eu.amidst.core.datastream.DataInstance;
import eu.amidst.core.datastream.DataStream;
import eu.amidst.core.models.DAG;
import eu.amidst.core.utils.DataSetGenerator;
import eu.amidst.core.variables.Variable;
import eu.amidst.standardmodels.exceptions.WrongConfigurationException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Created by andresmasegosa on 4/3/16.
 */
public class MixtureOfFactorAnalysers extends Model {

    private int numberOfLatentVariables = 5;
    private int numberOfStatesLatentDiscreteVar = 2;

    public MixtureOfFactorAnalysers(Attributes attributes) throws WrongConfigurationException {
        super(attributes);
    }

    public void setNumberOfLatentVariables(int numberOfLatentVariables) {
        this.numberOfLatentVariables = numberOfLatentVariables;
    }

    public void setNumberOfStatesLatentDiscreteVar(int numberOfStatesLatentDiscreteVar) {
        this.numberOfStatesLatentDiscreteVar = numberOfStatesLatentDiscreteVar;
    }

    @Override
    protected void buildDAG() {


        List<Variable> observableVariables = new ArrayList<>();
        List<Variable> latentVariables = new ArrayList<>();

        vars.forEach(observableVariables::add);

        Variable discreteLatentVar = vars.newMultionomialVariable("DiscreteLatentVar",numberOfStatesLatentDiscreteVar);

        IntStream.range(0,numberOfLatentVariables).forEach(i -> {
            Variable latentVar = vars.newGaussianVariable("LatentVar" + i);
            latentVariables.add(latentVar);
        });

        dag = new DAG(vars);

        for (Variable variable : observableVariables) {
            dag.getParentSet(variable).addParent(discreteLatentVar);
            latentVariables.forEach(latentVariable -> dag.getParentSet(variable).addParent(latentVariable));
        }

    }

    @Override
    public boolean isValidConfiguration() {

        boolean isValid  = vars.getListOfVariables().stream()
                .allMatch(Variable::isNormal);

        if(!isValid) {
            String errorMsg = "Invalid configuration: All variables must be real";
            this.setErrorMessage(errorMsg);
        }

        return isValid;
    }

    public static void main(String[] args) throws WrongConfigurationException {

        int seed=6236;
        int nSamples=5000;
        int nContinuousVars=10;

        DataStream<DataInstance> data = DataSetGenerator.generate(seed,nSamples,0,nContinuousVars);

        Model model = new MixtureOfFactorAnalysers(data.getAttributes());

        System.out.println(model.getDAG());

        model.learnModel(data);

//        for (DataOnMemory<DataInstance> batch : data.iterableOverBatches(1000)) {
//            model.updateModel(batch);
//        }

        System.out.println(model.getModel());

    }

}