

## Após a implementação da Issue 02.

```
{
  "expected": {
    "total": 54100,
    "fraud_count": 23959,
    "legit_count": 30141,
    "fraud_rate": 0.4429,
    "legit_rate": 0.5571,
    "edge_case_count": 645,
    "edge_case_rate": 0.0119
  },
  "p99": "80.33ms",
  "scoring": {
    "breakdown": {
      "false_positive_detections": 105,
      "false_negative_detections": 102,
      "true_positive_detections": 23840,
      "true_negative_detections": 30011,
      "http_errors": 0
    },
    "failure_rate": "0.38%",
    "weighted_errors_E": 411,
    "error_rate_epsilon": 0.007603,
    "p99_score": {
      "value": 1095.14,
      "cut_triggered": false
    },
    "detection_score": {
      "value": 1334.55,
      "rate_component": 2119.02,
      "absolute_penalty": -784.47,
      "cut_triggered": false
    },
    "final_score": 2429.69,
    "raw": {
      "p99_ms": 80.32601429,
      "failure_rate": 0.0038292204669059158,
      "error_rate_epsilon": 0.007602944985016094,
      "p99_score": 1095.1437818328538,
      "detection_score": 1334.548987251034,
      "rate_component": 2119.0181520609744,
      "absolute_penalty": -784.4691648099404,
      "final_score": 2429.692769083888
    }
  }
}
```