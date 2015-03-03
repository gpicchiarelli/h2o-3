package hex.naivebayes;

import hex.ModelMetrics;
import hex.SupervisedModel;
import hex.schemas.NaiveBayesModelV2;
import water.Key;
import water.api.ModelSchema;
import water.util.TwoDimTable;

public class NaiveBayesModel extends SupervisedModel<NaiveBayesModel,NaiveBayesModel.NaiveBayesParameters,NaiveBayesModel.NaiveBayesOutput> {
  public static class NaiveBayesParameters extends SupervisedModel.SupervisedParameters {
    public double _laplace = 0;         // Laplace smoothing parameter
    public double _min_sdev = 1e-10;   // Minimum standard deviation to use for observations without enough data
  }

  public static class NaiveBayesOutput extends SupervisedModel.SupervisedOutput {
    // Class counts of the dependent variable
    public double[] _rescnt;

    // Class distribution of the dependent variable
    public double[] _pprior;

    // For every predictor, a table providing, for each attribute level, the conditional probabilities given the target class
    public TwoDimTable[/*predictor*/] _pcond;
    public double[/*predictor*/][/*res_level*/][/*pred_level*/] _pcond_raw;

    // Number of categorical predictor variables
    public int _ncats;

    // Number of numeric predictor variables
    public int _nnums;

    // Model parameters
    NaiveBayesParameters _parameters;

    public NaiveBayesOutput(NaiveBayes b) { super(b); }

    @Override public ModelCategory getModelCategory() {
      return ModelCategory.Unknown;
    }
  }

  public NaiveBayesModel(Key selfKey, NaiveBayesParameters parms, NaiveBayesOutput output) { super(selfKey,parms,output); }

  public ModelSchema schema() {
    return new NaiveBayesModelV2();
  }

  @Override
  public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
    return null;
  }

  // Note: For small probabilities, product may end up zero due to underflow error. Can circumvent by taking logs.
  @Override
  protected float[] score0(double[] data, float[] preds) {
    double denom = 0;
    assert preds.length == (_output._pprior.length + 1);   // Note: First column of preds is predicted response class
    // Compute joint probability of predictors for every response class
    for(int rlevel = 0; rlevel < _output._pprior.length; rlevel++) {
      double num = 1;
      for(int col = 0; col < _output._ncats; col++) {
        if(Double.isNaN(data[col])) continue;   // Skip predictor in joint x_1,...,x_m if NA
        int plevel = (int)data[col];
        num *= _output._pcond_raw[col][rlevel][plevel];    // p(x|y) = \Pi_{j = 1}^m p(x_j|y)
      }

      // For numeric predictors, assume Gaussian distribution with sample mean and variance from model
      for(int col = _output._ncats; col < data.length; col++) {
        if(Double.isNaN(data[col])) continue;

        // Two ways to get non-zero std deviation HEX-1852
        // double stddev = pcond[col][rlevel][1] > 0 ? pcond[col][rlevel][1] : min_std_dev;  // only use the placeholder for critically low data
        double stddev = Math.max(_output._pcond_raw[col][rlevel][1], _parms._min_sdev); // more stable for almost constant data
        double mean = _output._pcond_raw[col][rlevel][0];
        double x = data[col];
        num *= Math.exp(-((x-mean)*(x-mean)/(2.*stddev*stddev)))/stddev/Math.sqrt(2.*Math.PI); // faster
        // num *= new NormalDistribution(mean, stddev).density(data[col]); //slower
      }

      num *= _output._pprior[rlevel];    // p(x,y) = p(x|y)*p(y)
      denom += num;                     // p(x) = \Sum_{levels of y} p(x,y)
      preds[rlevel+1] = (float)num;
    }

    // Select class with highest conditional probability
    float max = -1;
    for(int i = 1; i < preds.length; i++) {
      preds[i] /= denom;    // p(y|x) = p(x,y)/p(x)

      if(preds[i] > max) {
        max = preds[i];
        preds[0] = i-1;
      }
    }
    return preds;
  }
}
