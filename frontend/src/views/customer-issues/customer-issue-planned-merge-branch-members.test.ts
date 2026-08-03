import { describe, expect, it } from 'vitest';
import { parseCustomerIssuePlannedMergeBranchMembers } from './customer-issue-planned-merge-branch-members';

describe('customer issue planned merge branch members', () => {
  it('test_mixedSourceDelimiters_parse_returnsOrderedDistinctMembers', () => {
    expect(
      parseCustomerIssuePlannedMergeBranchMembers('dev, 26R1，26R2、26R3 & dev'),
    ).toEqual(['dev', '26R1', '26R2', '26R3']);
  });

  it('test_ampersandSeparatedBranches_parse_returnsEveryMember', () => {
    expect(
      parseCustomerIssuePlannedMergeBranchMembers('CC2026R4 & CC2026R5'),
    ).toEqual(['CC2026R4', 'CC2026R5']);
  });

  it('test_branchNamePunctuation_parse_preservesSingleBranch', () => {
    expect(
      parseCustomerIssuePlannedMergeBranchMembers(
        'crownCAD-Client:release/2026R3_feature.1',
      ),
    ).toEqual(['crownCAD-Client:release/2026R3_feature.1']);
  });

  it('test_emptyValue_parse_returnsEmptyMembers', () => {
    expect(parseCustomerIssuePlannedMergeBranchMembers(' & ， 、 ')).toEqual([]);
  });
});
