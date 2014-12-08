/**
 ******************* ISSUE LIST **************************
 *
 * 1. In the constructor, should we initialize the CLG attributes in this way?
 *
 * 2. The name of the method computeProbabilityOf(..) is a bit confusing for continuous domains. It does not compute probabilities but the
 * value for the density function which is not a probability. However as this class implements this method of ConditionalDistribution,
 * we could leave like this.
 *
 * 3. QAPlug gives a warning when using the same name for a attribute and a given argument, e.g. this.var = var
 * ********************************************************
 */

package eu.amidst.core.distribution;

import eu.amidst.core.variables.Assignment;
import eu.amidst.core.variables.Variable;

import java.util.Collections;
import java.util.List;

/**
 * <h2>This class implements a Conditional Linear Gaussian distribution, i.e. a distribution of a normal variable with
 * continuous normal parents.</h2>
 *
 * @author Antonio Fernández
 * @version 1.0
 * @since 2014-11-4
 */
public class Normal_NormalParents extends ConditionalDistribution {

    /**
     * The "intercept" parameter of the distribution
     */
    private double intercept;

    /**
     * The set of coefficients, one for each parent
     */
    private double[] coeffParents;

    /**
     * The standard deviation of the variable (it does not depends on the parents)
     */
    private double sd;

    /**
     * The class constructor.
     * @param var_ The variable of the distribution.
     * @param parents_ The set of parents of the variable.
     */
    public Normal_NormalParents(Variable var_, List<Variable> parents_) {

        this.var = var_;
        this.parents = parents_;
        this.intercept = 0;
        coeffParents = new double[parents.size()];
        for (int i = 0; i < parents.size(); i++) {
            coeffParents[i] = 1;
        }
        this.sd = 1;

        //Make them unmodifiable
        this.parents = Collections.unmodifiableList(this.parents);
    }

    /**
     * Gets the intercept of the distribution.
     * @return A <code>double</code> value with the intercept.
     */
    public double getIntercept() {
        return intercept;
    }

    /**
     * Sets the intercept of the distribution.
     * @param intercept_ A <code>double</code> value with the intercept.
     */
    public void setIntercept(double intercept_) {
        this.intercept = intercept_;
    }

    /**
     * Gets the coefficients for the parent variables.
     * @return An array of <code>double</code> with the coefficients.
     */
    public double[] getCoeffParents() {
        return coeffParents;
    }

    /**
     * Sets the coefficients of the distribution
     * @param coeffParents_ An array of <code>double</code> with the coefficients, one for each parent.
     */
    public void setCoeffParents(double[] coeffParents_) {
        this.coeffParents = coeffParents_;
    }

    /**
     * Gets the standard deviation of the variable.
     * @return A <code>double</code> value with the standard deviation.
     */
    public double getSd() {
        return sd;
    }

    /**
     * Sets the standard deviation of the variable.
     * @param sd_ A <code>double</code> value with the standard deviation.
     */
    public void setSd(double sd_) {
        this.sd = sd_;
    }

    /**
     * Gets the corresponding univariate normal distribution after conditioning the distribution to a parent assignment.
     * @param parentsAssignment An <code>Assignment</code> for the parents.
     * @return A <code>Normal</code> object with the univariate distribution.
     */
    public Normal getUnivariateNormal(Assignment parentsAssignment) {

        double mean = intercept;
        Normal univariateNormal = new Normal(var);
        int i = 0;

        for (Variable v : parents) {
            mean = mean + coeffParents[i] * parentsAssignment.getValue(v);
            i++;
        }

        univariateNormal.setSd(sd);
        univariateNormal.setMean(mean);

        return (univariateNormal);
    }


    @Override
    //Note that the intercept has not been included as a free parameter.
    public int getNumberOfFreeParameters() {
        return(coeffParents.length + 1);
    }


    /**
     * Computes the logarithm of the evaluated density function in a point after conditioning the distribution to a
     * given parent <code>Assignment</code>.
     * @param assignment An <code>Assignment</code>
     * @return A <code>double</code> with the logarithm of the corresponding density value.
     */
    @Override
    public double getLogConditionalProbability(Assignment assignment) {
        double value = assignment.getValue(this.var);
        return (getUnivariateNormal(assignment).getLogProbability(value));
    }

    public String label(){
        return "Normal|Normal";
    }


    public String toString(){
        StringBuilder str = new StringBuilder();
        str.append("[ alpha = " +this.getIntercept() + ", ");

        for (int i=0;i<this.getCoeffParents().length;i++){
            str.append("beta"+(i+1)+" = "+ this.getCoeffParents()[i] + ", ");
        }
        str.append("sd = " + this.getSd() + " ]");

        return str.toString();
    }
}
