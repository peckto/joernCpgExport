#!/usr/bin/env python3
import sys
import os
import json
import pandas as pd


def joern_json_csv(path):
    base = os.path.dirname(path)
    csv_dir = os.path.join(base, 'csv')
    if not os.path.exists(csv_dir):
        os.mkdir(csv_dir)

    j = json.load(open(path))
    vertex = pd.DataFrame(j['nodes'])
    edge = pd.DataFrame(j['edges'])
    d = {'nodes': []}
    for label in vertex['TYPE'].unique():
        df = vertex[vertex['TYPE'] == label]
        df = df.dropna(how='all', axis=1)
        df = df.rename(columns={'ID': f'{label}:ID', 'TYPE': ':LABEL'})
        f = os.path.join(csv_dir, f'vertex_{label}.csv')
        d['nodes'].append(f)
        df.to_csv(f, index=False)

    edge = edge.dropna(how='all', axis=1)
    edge = edge.rename(
        columns={'outV': ':END_ID', 'inV': ':START_ID', 'TYPE': ':TYPE'})
    f = os.path.join(csv_dir, 'edge.csv')
    d['relationships'] = f
    edge.to_csv(f, index=False)

    return d


if __name__ == '__main__':
    joern_json_csv(sys.argv[1])
